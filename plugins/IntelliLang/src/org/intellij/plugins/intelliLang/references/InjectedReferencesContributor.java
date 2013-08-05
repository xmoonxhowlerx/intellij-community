/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.intellij.plugins.intelliLang.references;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ProcessingContext;
import org.intellij.plugins.intelliLang.Configuration;
import org.intellij.plugins.intelliLang.inject.config.BaseInjection;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * @author Dmitry Avdeev
 *         Date: 01.08.13
 */
public class InjectedReferencesContributor extends PsiReferenceContributor {

  public static final Key<ReferenceInjector> INJECTED_REFERENCE = Key.create("injected reference");

  @Override
  public void registerReferenceProviders(PsiReferenceRegistrar registrar) {
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(), new PsiReferenceProvider() {
      @NotNull
      @Override
      public PsiReference[] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
        element.putUserData(INJECTED_REFERENCE, null);
        ReferenceInjector[] extensions = ReferenceInjector.EXTENSION_POINT_NAME.getExtensions();
        PsiReference[] references = new PsiReference[0];
        Configuration configuration = Configuration.getProjectInstance(element.getProject());
        for (ReferenceInjector extension : extensions) {
          Collection<BaseInjection> injections = configuration.getInjectionsByLanguageId(extension.getId());
          for (BaseInjection injection : injections) {
            if (injection.acceptForReference(element)) {
              element.putUserData(INJECTED_REFERENCE, extension);
              List<TextRange> area = injection.getInjectedArea(element);
              for (TextRange range : area) {
                references = ArrayUtil.mergeArrays(references, extension.getReferences(element, context, range));
              }
            }
          }
        }
        return references;
      }
    });
  }
}
