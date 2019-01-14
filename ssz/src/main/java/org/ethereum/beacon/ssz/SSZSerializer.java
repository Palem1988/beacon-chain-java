package org.ethereum.beacon.ssz;

import org.javatuples.Pair;
import net.consensys.cava.bytes.Bytes;
import net.consensys.cava.ssz.BytesSSZReaderProxy;
import org.ethereum.beacon.ssz.annotation.SSZ;
import org.ethereum.beacon.ssz.annotation.SSZSerializable;
import org.ethereum.beacon.ssz.annotation.SSZTransient;
import org.javatuples.Triplet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.ethereum.beacon.ssz.SSZSchemeBuilder.SSZScheme;

/**
 * <p>SSZ serializer/deserializer</p>
 */
public class SSZSerializer implements BytesSerializer {

  public final static int LENGTH_PREFIX_BYTE_SIZE = Integer.SIZE / Byte.SIZE;
  final static byte[] EMPTY_PREFIX = new byte[LENGTH_PREFIX_BYTE_SIZE];

  private SSZSchemeBuilder schemeBuilder;

  private SSZCodecResolver codecResolver;

  private SSZModelFactory sszModelFactory;

  /**
   * SSZ serializer/deserializer with following helpers
   * @param schemeBuilder     SSZ model scheme building of type {@link SSZScheme}
   * @param codecResolver     Resolves field encoder/decoder {@link org.ethereum.beacon.ssz.type.SSZCodec} function
   * @param sszModelFactory   Instantiates SSZModel with field/data information
   */
  public SSZSerializer(SSZSchemeBuilder schemeBuilder, SSZCodecResolver codecResolver,
                       SSZModelFactory sszModelFactory) {
    this.schemeBuilder = schemeBuilder;
    this.codecResolver = codecResolver;
    this.sszModelFactory = sszModelFactory;
  }

  /**
   * <p>Serializes input to byte[] data</p>
   * @param input  input value
   * @param clazz  Class of value
   * @return SSZ serialization
   */
  @Override
  public byte[] encode(@Nullable Object input, Class clazz) {
    checkSSZSerializableAnnotation(clazz);

    // Null check
    if (input == null) {
      return EMPTY_PREFIX;
    }

    // Fill up map with all available method getters
    Map<String, Method> getters = new HashMap<>();
    try {
      for (PropertyDescriptor pd : Introspector.getBeanInfo(clazz).getPropertyDescriptors()) {
        getters.put(pd.getReadMethod().getName(), pd.getReadMethod());
      }
    } catch (IntrospectionException e) {
      String error = String.format("Couldn't enumerate all getters in class %s",
          clazz.getName());
      throw new RuntimeException(error, e);
    }

    // Encode object fields one by one
    SSZScheme scheme = buildScheme(clazz);
    ByteArrayOutputStream res = new ByteArrayOutputStream();
    for (SSZScheme.SSZField field : scheme.fields) {
      Object value;
      Method getter = getters.get(field.getter);
      try {
        if (getter != null) {   // We have getter
          value = getter.invoke(input);
        } else {                // Trying to access field directly
          value = clazz.getField(field.name).get(input);
        }
      } catch (Exception e) {
        String error = String.format("Failed to get value from field %s, your should "
            + "either have public field or public getter for it", field.name);
        throw new SSZSchemeException(error);
      }

      codecResolver.resolveEncodeFunction(field).accept(new Triplet<>(value, res, this));
    }

    return res.toByteArray();
  }

  private static void checkSSZSerializableAnnotation(Class clazz) {
    if (!clazz.isAnnotationPresent(SSZSerializable.class)) {
      String error = String.format("Class %s should be annotated with SSZSerializable!", clazz);
      throw new SSZSchemeException(error);
    }
  }

  /**
   * Builds class scheme using {@link SSZSchemeBuilder}
   * @param clazz type class
   * @return SSZ model scheme
   */
  private SSZScheme buildScheme(Class clazz) {
    return schemeBuilder.build(clazz);
  }

  /**
   * <p>Restores data instance from serialization data using {@link SSZModelFactory}</p>
   * @param data     SSZ serialization byte[] data
   * @param clazz    type class
   * @return deserialized instance of clazz or throws exception
   */
  @Override
  public Object decode(byte[] data, Class clazz) {
    checkSSZSerializableAnnotation(clazz);

    // Fast null handling
    if (Arrays.equals(data, EMPTY_PREFIX)) {
      return null;
    }

    SSZScheme scheme = buildScheme(clazz);
    List<SSZScheme.SSZField> fields = scheme.fields;
    int size = fields.size();
    BytesSSZReaderProxy reader = new BytesSSZReaderProxy(Bytes.of(data));
    List<Pair<SSZSchemeBuilder.SSZScheme.SSZField, Object>> fieldValuePairs = new ArrayList<>();

    // For each field resolve its type and decode its value
    for (int i = 0; i < size; i++) {
      SSZScheme.SSZField field = fields.get(i);
      Object obj = codecResolver.resolveDecodeFunction(field).apply(new Pair<>(reader, this));
      fieldValuePairs.add(new Pair<>(field, obj));
    }

    return sszModelFactory.create(clazz, fieldValuePairs);
  }
}
