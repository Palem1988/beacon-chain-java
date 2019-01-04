package org.ethereum.beacon.util.ssz.type;

import net.consensys.cava.bytes.Bytes;
import net.consensys.cava.ssz.BytesSSZReaderProxy;
import net.consensys.cava.ssz.SSZ;
import org.ethereum.beacon.util.ssz.SSZSchemeBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Function.identity;

public class BytesPrimitive implements SSZEncoderDecoder {

  private static Set<String> supportedTypes = new HashSet<>();
  static {
    supportedTypes.add("bytes");
    supportedTypes.add("hash");
    supportedTypes.add("address");
  }

  private static Set<Class> supportedClassTypes = new HashSet<>();
  static {
    supportedClassTypes.add(byte[].class);
  }

  @Override
  public Set<String> getSupportedTypes() {
    return supportedTypes;
  }

  @Override
  public Set<Class> getSupportedClassTypes() {
    return supportedClassTypes;
  }

  @Override
  public void encode(Object value, SSZSchemeBuilder.SSZScheme.SSZField field, OutputStream result) {
    BytesType byteType = parseFieldType(field);
    Bytes res = null;
    byte[] data = (byte[]) value;

    switch (byteType.type) {
      case HASH: {
        res = SSZ.encodeHash(Bytes.of(data));
        break;
      }
      case ADDRESS: {
        res = SSZ.encodeAddress(Bytes.of(data));
        break;
      }
      case BYTES: {
        res = SSZ.encodeByteArray(data);
        break;
      }
      default: {
        throwUnsupportedType(field);
      }
    }

    try {
      result.write(res.toArrayUnsafe());
    } catch (IOException e) {
      String error = String.format("Failed to write data of type %s to stream", field.type);
      throw new RuntimeException(error, e);
    }
  }

  @Override
  public void encodeList(List<Object> value, SSZSchemeBuilder.SSZScheme.SSZField field, OutputStream result) {
    BytesType bytesType = parseFieldType(field);
    Bytes[] data = repackBytesList((List<byte[]>) (List<?>) value);

    try {
      switch (bytesType.type) {
        case HASH: {
          result.write(SSZ.encodeHashList(data).toArrayUnsafe());
          break;
        }
        case BYTES: {
          result.write(SSZ.encodeBytesList(data).toArrayUnsafe());
          break;
        }
        case ADDRESS: {
          result.write(SSZ.encodeAddressList(data).toArrayUnsafe());
          break;
        }
        default: {
          throwUnsupportedType(field);
        }
      }
    } catch (IOException ex) {
      String error = String.format("Failed to write data from field \"%s\" to stream",
          field.name);
      throw new RuntimeException(error, ex);
    }
  }

  private static Bytes[] repackBytesList(List<byte[]> list) {
    Bytes[] data = new Bytes[list.size()];
    for (int i = 0; i < list.size(); i++) {
      byte[] el = list.get(i);
      data[i] = Bytes.of(el);
    }

    return data;
  }

  @Override
  public Object decode(SSZSchemeBuilder.SSZScheme.SSZField field, BytesSSZReaderProxy reader) {
    BytesType bytesType = parseFieldType(field);
    switch (bytesType.type) {
      case BYTES: {
        return (bytesType.size == null)
            ? reader.readBytes().toArrayUnsafe()
            : reader.readBytes(bytesType.size).toArrayUnsafe();
      }
      case HASH: {
        return reader.readHash(bytesType.size).toArrayUnsafe();
      }
      case ADDRESS: {
        return reader.readAddress().toArrayUnsafe();
      }
    }

    return throwUnsupportedType(field);
  }

  @Override
  public List<Object> decodeList(SSZSchemeBuilder.SSZScheme.SSZField field, BytesSSZReaderProxy reader) {
    BytesType bytesType = parseFieldType(field);

    switch (bytesType.type) {
      case BYTES: {
        return (List<Object>) (List<?>) reader.readByteArrayList();
      }
      case HASH: {
        return (List<Object>) (List<?>) reader.readHashList(bytesType.size);
      }
      case ADDRESS: {
        return (List<Object>) (List<?>) reader.readAddressList();
      }

      default: {
        return throwUnsupportedListType(field);
      }
    }
  }

  static class BytesType {
    Type type;
    Integer size;

    public BytesType() {
    }

    static BytesType of(Type type, Integer size) {
      BytesType res = new BytesType();
      res.type = type;
      res.size = size;
      return res;
    }
  }

  enum Type {
    BYTES("bytes"),
    HASH("hash"),
    ADDRESS("address");

    private String type;
    private static final Map<String, Type> ENUM_MAP;
    static {
      ENUM_MAP = Stream.of(Type.values()).collect(Collectors.toMap(e -> e.type, identity()));
    }

    Type(String type) {
      this.type = type;
    }

    static Type fromValue(String type) {
      return ENUM_MAP.get(type);
    }

    @Override
    public String toString() {
      return type;
    }
  }

  private BytesType parseFieldType(SSZSchemeBuilder.SSZScheme.SSZField field) {
    Type type = Type.fromValue(field.extraType);

    if (type == null || type.equals(Type.BYTES)) {
      return BytesType.of(Type.BYTES, field.extraSize);
    }

    if (type.equals(Type.ADDRESS)) {
      if (field.extraSize != null) {
        throw new RuntimeException("Address is fixed 20 bytes type");
      } else {
        return BytesType.of(Type.ADDRESS, 20);
      }
    } else {
      if (field.extraSize == null) {
        throw new RuntimeException("Hash size is required!");
      } else {
        return BytesType.of(Type.HASH, field.extraSize);
      }
    }
  }
}
