package org.ethereum.beacon.ssz.type.list;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import org.ethereum.beacon.ssz.SSZSchemeBuilder.SSZScheme.SSZField;
import org.ethereum.beacon.ssz.type.SSZListAccessor;

public abstract class AbstractListAccessor implements SSZListAccessor {

  abstract class SimpleInstanceBuilder implements InstanceBuilder {
    private List<Object> children = new ArrayList<>();

    @Override
    public void addChild(Object childValue) {
      children.add(childValue);
    }

    @Override
    public void setChild(int idx, Object childValue) {
      if (idx == children.size()) {
        children.add(childValue);
      } else {
        children.set(idx, childValue);
      }
    }

    @Override
    public Object build() {
      return buildImpl(children);
    }

    protected abstract Object buildImpl(List<Object> children) ;
  }

  static SSZField extractElementType(SSZField listDescriptor, int genericTypeParamIndex) {
    SSZField sszField = new SSZField();
    if (listDescriptor.fieldGenericType == null) {
      sszField.fieldType = Object.class;
    } else {
      Type listTypeArgument = listDescriptor.fieldGenericType
          .getActualTypeArguments()[genericTypeParamIndex];

      if (listTypeArgument instanceof WildcardType) {
        listTypeArgument = ((WildcardType) listTypeArgument).getLowerBounds()[0];
      }

      if (listTypeArgument instanceof Class) {
        sszField.fieldType = (Class<?>) listTypeArgument;
      } else if (listTypeArgument instanceof ParameterizedType) {
        sszField.fieldType = (Class<?>) ((ParameterizedType) listTypeArgument).getRawType();
        sszField.fieldGenericType = (ParameterizedType) listTypeArgument;
      } else {
        throw new RuntimeException("Internal error: unknown list type: " + listTypeArgument);
      }
    }
    return sszField;
  }
}
