package org.ethereum.beacon.ssz;

import net.consensys.cava.bytes.Bytes;
import net.consensys.cava.ssz.BytesSSZReaderProxy;
import net.consensys.cava.ssz.SSZException;
import org.ethereum.beacon.ssz.SSZSchemeBuilder.SSZScheme.SSZField;
import org.ethereum.beacon.ssz.type.SSZCodec;
import org.ethereum.beacon.ssz.type.SSZListAccessor;
import org.ethereum.beacon.ssz.type.SubclassCodec;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.ethereum.beacon.ssz.SSZSerializer.LENGTH_PREFIX_BYTE_SIZE;

/**
 * Implementation of {@link SSZCodec} which handles unknown classes recursively, passing it to input
 * {@link BytesSerializer} instance, and prioritizes codec supported class over supported type.
 *
 * <p>So, if handled field class has only one codec registered for, it will be used, even if field
 * have text marking that matches two codecs. But if several codecs are registered for one class and
 * it has {@link org.ethereum.beacon.ssz.SSZSchemeBuilder.SSZScheme.SSZField#extraType} marking, it
 * will be handled by codec supporting this class and type.
 */
public class SSZCodecRoulette implements SSZCodecResolver {
  private Map<Class, List<CodecEntry>> registeredClassHandlers = new HashMap<>();

  @Override
  public Consumer<Triplet<Object, OutputStream, BytesSerializer>> resolveEncodeFunction(
      SSZField field) {
    return null;
  }

  public Function<Pair<BytesSSZReaderProxy, BytesSerializer>, Object> resolveDecodeFunction(
      SSZSchemeBuilder.SSZScheme.SSZField field) {
    SSZCodec decoder = resolveBasicValueCodec(field);
    if (field.multipleType.equals(SSZSchemeBuilder.SSZScheme.MultipleType.NONE)) {
      if (decoder != null) {
        return objects -> {
          BytesSSZReaderProxy reader = objects.getValue0();
          return decoder.decode(field, reader);
        };
      } else {
        return objects -> {
          BytesSSZReaderProxy reader = objects.getValue0();
          BytesSerializer sszSerializer = objects.getValue1();
          return decodeContainer(field, reader, sszSerializer);
        };
      }
    } else if (field.multipleType.equals(SSZSchemeBuilder.SSZScheme.MultipleType.LIST)) {
      if (decoder != null) {
        return objects -> {
          BytesSSZReaderProxy reader = objects.getValue0();
          return decoder.decodeList(field, reader);
        };
      } else {
        return objects -> {
          BytesSSZReaderProxy reader = objects.getValue0();
          BytesSerializer sszSerializer = objects.getValue1();
          return decodeContainerList(field, reader, sszSerializer);
        };
      }
    } else if (field.multipleType.equals(SSZSchemeBuilder.SSZScheme.MultipleType.ARRAY)) {
      return objects -> {
        Object[] uncastedResult;
        BytesSSZReaderProxy reader = objects.getValue0();
        BytesSerializer sszSerializer = objects.getValue1();
        if (decoder != null) {
          uncastedResult = decoder.decodeArray(field, reader);
        } else {
          List<Object> list = decodeContainerList(field, reader, sszSerializer);
          uncastedResult = list.toArray();
        }
        Object[] res = (Object[]) Array.newInstance(field.fieldType, uncastedResult.length);
        System.arraycopy(uncastedResult, 0, res, 0, uncastedResult.length);

        return res;
      };
    }

    throw new SSZSchemeException(
        String.format("Function not resolved for decoding field %s", field));
  }

  private Object decodeContainer(
      SSZSchemeBuilder.SSZScheme.SSZField field,
      BytesSSZReaderProxy reader,
      BytesSerializer sszSerializer) {
    return decodeContainerImpl(field, reader, sszSerializer).getValue0();
  }

  private Pair<Object, Integer> decodeContainerImpl(
      SSZSchemeBuilder.SSZScheme.SSZField field,
      BytesSSZReaderProxy reader,
      BytesSerializer sszSerializer) {
    Bytes data = reader.readBytes();
    int dataSize = data.size();

    if (field.notAContainer) {
      Bytes lengthPrefix = net.consensys.cava.ssz.SSZ.encodeUInt32(dataSize);
      byte[] container = Bytes.concatenate(lengthPrefix, data).toArrayUnsafe();
      return new Pair<>(sszSerializer.decode(container, field.fieldType), dataSize);
    } else {
      return new Pair<>(
          sszSerializer.decode(data.toArrayUnsafe(), field.fieldType),
          dataSize + LENGTH_PREFIX_BYTE_SIZE);
    }
  }

  private List<Object> decodeContainerList(
      SSZSchemeBuilder.SSZScheme.SSZField field,
      BytesSSZReaderProxy reader,
      BytesSerializer sszSerializer) {
    int remainingData = reader.readInt32();
    List<Object> res = new ArrayList<>();
    while (remainingData > 0) {
      Pair<Object, Integer> decodeRes = decodeContainerImpl(field, reader, sszSerializer);
      res.add(decodeRes.getValue0());
      remainingData -= decodeRes.getValue1();
    }
    return res;
  }

  public SSZCodec resolveBasicValueCodec(SSZSchemeBuilder.SSZScheme.SSZField field) {
    Class<?> type = field.fieldType;
    boolean subclassCodec = false;
    if (!SubclassCodec.getSerializableClass(type).equals(type)) {
      type = SubclassCodec.getSerializableClass(type);
      subclassCodec = true;
    }

    SSZCodec codec = null;
    if (registeredClassHandlers.containsKey(type)) {
      List<CodecEntry> codecs = registeredClassHandlers.get(type);
      if (field.extraType == null || field.extraType.isEmpty()) {
        codec = codecs.get(0).codec;
      } else {
        for (CodecEntry codecEntry : codecs) {
          if (codecEntry.types.contains(field.extraType)) {
            codec = codecEntry.codec;
            break;
          }
        }
      }
    }

    if (codec != null && subclassCodec) {
      codec = new SubclassCodec(codec);
    }

    return codec;
  }

  @Override
  public SSZListAccessor resolveListValueAccessor(SSZField field) {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers codecs to be used for
   *
   * @param classes Classes, resolving is performed with class at first
   * @param types Text type, one class could be interpreted to several types. Several codecs could
   *     handle one class. Empty/null type is occupied by first class codec. Type is looked up in
   *     codecs one by one.
   * @param codec Codec able to encode/decode of specific class/types
   */
  public void registerCodec(Set<Class> classes, Set<String> types, SSZCodec codec) {
    for (Class clazz : classes) {
      if (registeredClassHandlers.get(clazz) != null) {
        registeredClassHandlers.get(clazz).add(new CodecEntry(codec, types));
      } else {
        registeredClassHandlers.put(
            clazz, new ArrayList<>(Collections.singletonList(new CodecEntry(codec, types))));
      }
    }
  }

  class CodecEntry {
    SSZCodec codec;
    Set<String> types;

    public CodecEntry(SSZCodec codec, Set<String> types) {
      this.codec = codec;
      this.types = types;
    }
  }
}
