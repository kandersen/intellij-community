package org.jetbrains.plugins.javaFX.fxml.descriptors;

import com.intellij.codeInsight.daemon.impl.analysis.GenericsHighlightUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Processor;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlElementsGroup;
import com.intellij.xml.XmlNSDescriptor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.javaFX.fxml.FxmlConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * User: anna
 * Date: 1/10/13
 */
public class JavaFxPropertyElementDescriptor implements XmlElementDescriptor {
  private final PsiClass myPsiClass;
  private final String myName;
  private final boolean myStatic;

  public JavaFxPropertyElementDescriptor(PsiClass psiClass, String name, boolean isStatic) {
    myPsiClass = psiClass;
    myName = name;
    myStatic = isStatic;
  }

  @Override
  public String getQualifiedName() {
    return getName();
  }

  @Override
  public String getDefaultName() {
    return getName();
  }

  @Override
  public XmlElementDescriptor[] getElementsDescriptors(XmlTag context) {
    final PsiElement declaration = getDeclaration();
    if (declaration instanceof PsiField) {
      final PsiType psiType = ((PsiField)declaration).getType();
      final ArrayList<XmlElementDescriptor> descriptors = new ArrayList<XmlElementDescriptor>();
      collectDescriptorsByCollection(psiType, declaration.getResolveScope(), descriptors);
      for (String name : FxmlConstants.FX_DEFAULT_ELEMENTS) {
        descriptors.add(new JavaFxDefaultPropertyElementDescriptor(name, null));
      }
      if (!descriptors.isEmpty()) return descriptors.toArray(new XmlElementDescriptor[descriptors.size()]);
    }
    return XmlElementDescriptor.EMPTY_ARRAY;
  }

  public static void collectDescriptorsByCollection(PsiType psiType,
                                                    GlobalSearchScope resolveScope,
                                                    final List<XmlElementDescriptor> descriptors) {
    final PsiType collectionItemType = GenericsHighlightUtil.getCollectionItemType(psiType, resolveScope);
    if (collectionItemType != null) {
      final PsiClass aClass = PsiUtil.resolveClassInType(collectionItemType);
      if (aClass != null) {
        ClassInheritorsSearch.search(aClass).forEach(new Processor<PsiClass>() {
          @Override
          public boolean process(PsiClass aClass) {
            descriptors.add(new JavaFxClassBackedElementDescriptor(aClass.getName(), aClass));
            return true;
          }
        });
      }
    }
  }

  @Nullable
  @Override
  public XmlElementDescriptor getElementDescriptor(XmlTag childTag, XmlTag contextTag) {
    final String name = childTag.getName();
    if (FxmlConstants.FX_DEFAULT_ELEMENTS.contains(name)) {
      return new JavaFxDefaultPropertyElementDescriptor(name, childTag);
    }
    if (JavaFxClassBackedElementDescriptor.isClassTag(name)) {
      return new JavaFxClassBackedElementDescriptor(name, childTag);
    }
    else if (myPsiClass != null) {
      return new JavaFxPropertyElementDescriptor(myPsiClass, name, name.indexOf('.') > 0);
    }
    return null;
  }

  @Override
  public XmlAttributeDescriptor[] getAttributesDescriptors(@Nullable XmlTag context) {
    return XmlAttributeDescriptor.EMPTY;
  }

  @Nullable
  @Override
  public XmlAttributeDescriptor getAttributeDescriptor(@NonNls String attributeName, @Nullable XmlTag context) {
    return null;
  }

  @Nullable
  @Override
  public XmlAttributeDescriptor getAttributeDescriptor(XmlAttribute attribute) {
    return getAttributeDescriptor(attribute.getName(), attribute.getParent());
  }

  @Override
  public XmlNSDescriptor getNSDescriptor() {
    return null;
  }

  @Nullable
  @Override
  public XmlElementsGroup getTopGroup() {
    return null;
  }

  @Override
  public int getContentType() {
    return CONTENT_TYPE_UNKNOWN;
  }

  @Nullable
  @Override
  public String getDefaultValue() {
    return null;
  }

  @Override
  public PsiElement getDeclaration() {
    if (myPsiClass == null) return null;
    final PsiField field = myPsiClass.findFieldByName(myName, true);
    if (field != null) {
      return field;
    }
    return JavaFxClassBackedElementDescriptor.findPropertySetter(myName, myPsiClass);
  }

  @Override
  public String getName(PsiElement context) {
    return getName();
  }

  @Override
  public String getName() {
    if (myPsiClass != null && myStatic) {
      return StringUtil.getQualifiedName(myPsiClass.getName(), myName);
    }
    return myName;
  }

  @Override
  public void init(PsiElement element) {}

  @Override
  public Object[] getDependences() {
    return ArrayUtil.EMPTY_OBJECT_ARRAY;
  }
}
