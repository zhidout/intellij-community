/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: dsl
 * Date: 06.05.2002
 * Time: 13:36:30
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.refactoring.introduceParameter;

import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.FunctionalInterfaceSuggester;
import com.intellij.codeInsight.completion.JavaCompletionUtil;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.ide.util.PsiClassListCellRenderer;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupAdapter;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.HelpID;
import com.intellij.refactoring.IntroduceHandlerBase;
import com.intellij.refactoring.IntroduceParameterRefactoring;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.extractMethod.AbstractExtractDialog;
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor;
import com.intellij.refactoring.extractMethod.InputVariables;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import com.intellij.refactoring.introduce.inplace.AbstractInplaceIntroducer;
import com.intellij.refactoring.introduceField.ElementToWorkOn;
import com.intellij.refactoring.ui.MethodCellRenderer;
import com.intellij.refactoring.ui.NameSuggestionsGenerator;
import com.intellij.refactoring.ui.TypeSelectorManagerImpl;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.refactoring.util.VariableData;
import com.intellij.refactoring.util.occurrences.ExpressionOccurrenceManager;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBList;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.PairConsumer;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;


public class IntroduceParameterHandler extends IntroduceHandlerBase {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.introduceParameter.IntroduceParameterHandler");
  static final String REFACTORING_NAME = RefactoringBundle.message("introduce.parameter.title");
  private JBPopup myEnclosingMethodsPopup;
  private InplaceIntroduceParameterPopup myInplaceIntroduceParameterPopup;

  public void invoke(@NotNull final Project project, final Editor editor, final PsiFile file, DataContext dataContext) {
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    ElementToWorkOn.processElementToWorkOn(editor, file, REFACTORING_NAME, HelpID.INTRODUCE_PARAMETER, project, new ElementToWorkOn.ElementsProcessor<ElementToWorkOn>() {
      @Override
      public boolean accept(ElementToWorkOn el) {
        return true;
      }

      @Override
      public void pass(final ElementToWorkOn elementToWorkOn) {
        if (elementToWorkOn == null) {
          introduceStrategy(project, editor, file);
          return;
        }

        final PsiExpression expr = elementToWorkOn.getExpression();
        final PsiLocalVariable localVar = elementToWorkOn.getLocalVariable();
        final boolean isInvokedOnDeclaration = elementToWorkOn.isInvokedOnDeclaration();

        invoke(editor, project, expr, localVar, isInvokedOnDeclaration);
      }
    });
  }

  protected boolean invokeImpl(Project project, PsiExpression tempExpr, Editor editor) {
    return invoke(editor, project, tempExpr, null, false);
  }

  protected boolean invokeImpl(Project project, PsiLocalVariable localVariable, Editor editor) {
    return invoke(editor, project, null, localVariable, true);
  }

  private boolean invoke(final Editor editor, final Project project, final PsiExpression expr,
                         PsiLocalVariable localVar, boolean invokedOnDeclaration) {
    LOG.assertTrue(!PsiDocumentManager.getInstance(project).hasUncommitedDocuments());
    PsiMethod method;
    if (expr != null) {
      method = Util.getContainingMethod(expr);
    }
    else {
      method = Util.getContainingMethod(localVar);
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("expression:" + expr);
    }

    if (expr == null && localVar == null) {
      String message =  RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("selected.block.should.represent.an.expression"));
      showErrorMessage(project, message, editor);
      return false;
    }

    if (localVar != null) {
      final PsiElement parent = localVar.getParent();
      if (!(parent instanceof PsiDeclarationStatement)) {
        String message = RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("error.wrong.caret.position.local.or.expression.name"));
        showErrorMessage(project, message, editor);
        return false;
      }
    }

    if (method == null) {
      String message = RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("is.not.supported.in.the.current.context", REFACTORING_NAME));
      showErrorMessage(project, message, editor);
      return false;
    }

    final PsiType typeByExpression = invokedOnDeclaration ? null : RefactoringUtil.getTypeByExpressionWithExpectedType(expr);
    if (!invokedOnDeclaration && (typeByExpression == null || LambdaUtil.notInferredType(typeByExpression))) {
      String message = RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("type.of.the.selected.expression.cannot.be.determined"));
      showErrorMessage(project, message, editor);
      return false;
    }

    if (!invokedOnDeclaration && PsiType.VOID.equals(typeByExpression)) {
      String message = RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("selected.expression.has.void.type"));
      showErrorMessage(project, message, editor);
      return false;
    }

    final List<PsiMethod> validEnclosingMethods = getEnclosingMethods(method);
    if (validEnclosingMethods.isEmpty()) {
      return false;
    }

    if (!CommonRefactoringUtil.checkReadOnlyStatus(project, method)) return false;

    final Introducer introducer = new Introducer(project, expr, localVar, editor);
    final AbstractInplaceIntroducer inplaceIntroducer = AbstractInplaceIntroducer.getActiveIntroducer(editor);
    if (inplaceIntroducer instanceof InplaceIntroduceParameterPopup) {
      final InplaceIntroduceParameterPopup introduceParameterPopup = (InplaceIntroduceParameterPopup)inplaceIntroducer;
      introducer.introduceParameter(introduceParameterPopup.getMethodToIntroduceParameter(),
                                    introduceParameterPopup.getMethodToSearchFor());
      return true;
    }

    chooseMethodToIntroduceParameter(editor, validEnclosingMethods, new PairConsumer<PsiMethod, PsiMethod>() {
      @Override
      public void consume(PsiMethod methodToSearchIn, PsiMethod methodToSearchFor) {
        introducer.introduceParameter(methodToSearchIn, methodToSearchFor);
      }
    });

    return true;
  }

  private void chooseMethodToIntroduceParameter(final Editor editor,
                                                final List<PsiMethod> validEnclosingMethods,
                                                final PairConsumer<PsiMethod, PsiMethod> consumer) {
    final boolean unitTestMode = ApplicationManager.getApplication().isUnitTestMode();
    if (validEnclosingMethods.size() == 1 || unitTestMode) {
      final PsiMethod methodToIntroduceParameterTo = validEnclosingMethods.get(0);
      if (methodToIntroduceParameterTo.findDeepestSuperMethod() == null || unitTestMode) {
        consumer.consume(methodToIntroduceParameterTo, methodToIntroduceParameterTo);
        return;
      }
    }

    final JPanel panel = new JPanel(new BorderLayout());
    final JCheckBox superMethod = new JCheckBox("Refactor super method", true);
    superMethod.setMnemonic('U');
    panel.add(superMethod, BorderLayout.SOUTH);
    final JBList list = new JBList(validEnclosingMethods.toArray());
    list.setVisibleRowCount(5);
    list.setCellRenderer(new MethodCellRenderer());
    list.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setSelectedIndex(0);
    final List<RangeHighlighter> highlighters = new ArrayList<RangeHighlighter>();
    final TextAttributes attributes =
      EditorColorsManager.getInstance().getGlobalScheme().getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES);
    list.addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(final ListSelectionEvent e) {
        final PsiMethod selectedMethod = (PsiMethod)list.getSelectedValue();
        if (selectedMethod == null) return;
        dropHighlighters(highlighters);
        updateView(selectedMethod, editor, attributes, highlighters, superMethod);
      }
    });
    updateView(validEnclosingMethods.get(0), editor, attributes, highlighters, superMethod);
    final JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(list);
    scrollPane.setBorder(null);
    panel.add(scrollPane, BorderLayout.CENTER);

    final List<Pair<ActionListener, KeyStroke>>
      keyboardActions = Collections.singletonList(Pair.<ActionListener, KeyStroke>create(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
          final PsiMethod methodToSearchIn = (PsiMethod)list.getSelectedValue();
          if (myEnclosingMethodsPopup != null && myEnclosingMethodsPopup.isVisible()) {
            myEnclosingMethodsPopup.cancel();
          }

          final PsiMethod methodToSearchFor = superMethod.isEnabled() && superMethod.isSelected()
                                              ? methodToSearchIn.findDeepestSuperMethod() : methodToSearchIn;
          Runnable runnable = new Runnable() {
            public void run() {
              consumer.consume(methodToSearchIn, methodToSearchFor);
            }
          };
          IdeFocusManager.findInstance().doWhenFocusSettlesDown(runnable);
        }
      }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
    myEnclosingMethodsPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, list)
      .setTitle("Introduce parameter to method")
      .setMovable(false)
      .setResizable(false)
      .setRequestFocus(true)
      .setKeyboardActions(keyboardActions).addListener(new JBPopupAdapter() {
        @Override
        public void onClosed(LightweightWindowEvent event) {
          dropHighlighters(highlighters);
        }
      }).createPopup();
    myEnclosingMethodsPopup.showInBestPositionFor(editor);
  }

  private static void updateView(PsiMethod selectedMethod,
                                 Editor editor,
                                 TextAttributes attributes,
                                 List<RangeHighlighter> highlighters,
                                 JCheckBox superMethod) {
    final MarkupModel markupModel = editor.getMarkupModel();
    final PsiIdentifier nameIdentifier = selectedMethod.getNameIdentifier();
    if (nameIdentifier != null) {
      final TextRange textRange = nameIdentifier.getTextRange();
      final RangeHighlighter rangeHighlighter = markupModel.addRangeHighlighter(
        textRange.getStartOffset(), textRange.getEndOffset(), HighlighterLayer.SELECTION - 1,
        attributes,
        HighlighterTargetArea.EXACT_RANGE);
      highlighters.add(rangeHighlighter);
    }
    superMethod.setEnabled(selectedMethod.findDeepestSuperMethod() != null);
  }

  private static void dropHighlighters(List<RangeHighlighter> highlighters) {
    for (RangeHighlighter highlighter : highlighters) {
      highlighter.dispose();
    }
    highlighters.clear();
  }

  protected static NameSuggestionsGenerator createNameSuggestionGenerator(final PsiExpression expr,
                                                                          final String propName,
                                                                          final Project project,
                                                                          final String enteredName) {
    return new NameSuggestionsGenerator() {
      public SuggestedNameInfo getSuggestedNameInfo(PsiType type) {
        final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
        SuggestedNameInfo info = codeStyleManager.suggestVariableName(VariableKind.PARAMETER, propName, expr != null && expr.isValid() ? expr : null, type);
        if (expr != null && expr.isValid()) {
          info = codeStyleManager.suggestUniqueVariableName(info, expr, true);
        }
        final String[] strings = AbstractJavaInplaceIntroducer.appendUnresolvedExprName(JavaCompletionUtil
          .completeVariableNameForRefactoring(codeStyleManager, type, VariableKind.LOCAL_VARIABLE, info), expr);
        return new SuggestedNameInfo.Delegate(enteredName != null ? ArrayUtil.mergeArrays(new String[]{enteredName}, strings): strings, info);
      }

    };
  }

  private static void showErrorMessage(Project project, String message, Editor editor) {
    CommonRefactoringUtil.showErrorHint(project, editor, message, REFACTORING_NAME, HelpID.INTRODUCE_PARAMETER);
  }


  public void invoke(@NotNull Project project, @NotNull PsiElement[] elements, DataContext dataContext) {
    // Never called
    /* do nothing */
  }

  public static List<PsiMethod> getEnclosingMethods(PsiMethod nearest) {
    List<PsiMethod> enclosingMethods = new ArrayList<PsiMethod>();
    enclosingMethods.add(nearest);
    PsiMethod method = nearest;
    while(true) {
      method = PsiTreeUtil.getParentOfType(method, PsiMethod.class, true);
      if (method == null) break;
      enclosingMethods.add(method);
    }
    if (enclosingMethods.size() > 1) {
      List<PsiMethod> methodsNotImplementingLibraryInterfaces = new ArrayList<PsiMethod>();
      for(PsiMethod enclosing: enclosingMethods) {
        PsiMethod[] superMethods = enclosing.findDeepestSuperMethods();
        boolean libraryInterfaceMethod = false;
        for(PsiMethod superMethod: superMethods) {
          libraryInterfaceMethod |= isLibraryInterfaceMethod(superMethod);
        }
        if (!libraryInterfaceMethod) {
          methodsNotImplementingLibraryInterfaces.add(enclosing);
        }
      }
      if (methodsNotImplementingLibraryInterfaces.size() > 0) {
        return methodsNotImplementingLibraryInterfaces;
      }
    }
    return enclosingMethods;
  }


  @Nullable
  public static PsiMethod chooseEnclosingMethod(@NotNull PsiMethod method) {
    final List<PsiMethod> validEnclosingMethods = getEnclosingMethods(method);
    if (validEnclosingMethods.size() > 1 && !ApplicationManager.getApplication().isUnitTestMode()) {
      final EnclosingMethodSelectionDialog dialog = new EnclosingMethodSelectionDialog(method.getProject(), validEnclosingMethods);
      if (!dialog.showAndGet()) {
        return null;
      }
      method = dialog.getSelectedMethod();
    }
    else if (validEnclosingMethods.size() == 1) {
      method = validEnclosingMethods.get(0);
    }
    return method;
  }

  private static boolean isLibraryInterfaceMethod(final PsiMethod method) {
    return method.hasModifierProperty(PsiModifier.ABSTRACT) && !method.getManager().isInProject(method);
  }

  private class Introducer {

    private final Project myProject;

    private PsiExpression myExpr;
    private PsiLocalVariable myLocalVar;
    private final Editor myEditor;

    public Introducer(Project project,
                      PsiExpression expr,
                      PsiLocalVariable localVar,
                      Editor editor) {
      myProject = project;
      myExpr = expr;
      myLocalVar = localVar;
      myEditor = editor;
    }

    public void introduceParameter(PsiMethod method, PsiMethod methodToSearchFor) {
      PsiExpression[] occurences;
      if (myExpr != null) {
        occurences = new ExpressionOccurrenceManager(myExpr, method, null).findExpressionOccurrences();
      }
      else { // local variable
        occurences = CodeInsightUtil.findReferenceExpressions(method, myLocalVar);
      }

      String enteredName = null;
      boolean replaceAllOccurrences = false;
      boolean delegate = false;
      PsiType initializerType = IntroduceParameterProcessor.getInitializerType(null, myExpr, myLocalVar);

      final AbstractInplaceIntroducer activeIntroducer = AbstractInplaceIntroducer.getActiveIntroducer(myEditor);
      if (activeIntroducer != null) {
        activeIntroducer.stopIntroduce(myEditor);
        myExpr = (PsiExpression)activeIntroducer.getExpr();
        myLocalVar = (PsiLocalVariable)activeIntroducer.getLocalVariable();
        occurences = (PsiExpression[])activeIntroducer.getOccurrences();
        enteredName = activeIntroducer.getInputName();
        replaceAllOccurrences = activeIntroducer.isReplaceAllOccurrences();
        delegate = ((InplaceIntroduceParameterPopup)activeIntroducer).isGenerateDelegate();
        initializerType = ((AbstractJavaInplaceIntroducer)activeIntroducer).getType();
      }

      boolean mustBeFinal = false;
      for (PsiExpression occurrence : occurences) {
        if (PsiTreeUtil.getParentOfType(occurrence, PsiClass.class, PsiMethod.class) != method) {
          mustBeFinal = true;
          break;
        }
      }

      final String propName = myLocalVar != null ? JavaCodeStyleManager
        .getInstance(myProject).variableNameToPropertyName(myLocalVar.getName(), VariableKind.LOCAL_VARIABLE) : null;

      boolean isInplaceAvailableOnDataContext = myEditor != null && myEditor.getSettings().isVariableInplaceRenameEnabled();

      if (myExpr != null) {
        isInplaceAvailableOnDataContext &= myExpr.isPhysical();
      }

      if (isInplaceAvailableOnDataContext && activeIntroducer == null) {
        myInplaceIntroduceParameterPopup =
          new InplaceIntroduceParameterPopup(myProject, myEditor,
                                             createTypeSelectorManager(occurences, initializerType),
                                             myExpr, myLocalVar, method, methodToSearchFor, occurences,
                                             getParamsToRemove(method, occurences),
                                             mustBeFinal);
        if (myInplaceIntroduceParameterPopup.startInplaceIntroduceTemplate()) {
          return;
        }
      }
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        @NonNls String parameterName = "anObject";
        boolean replaceAllOccurences = true;
        boolean isDeleteLocalVariable = true;
        PsiExpression initializer = myLocalVar != null && myExpr == null ? myLocalVar.getInitializer() : myExpr;
        new IntroduceParameterProcessor(myProject, method, methodToSearchFor, initializer, myExpr, myLocalVar, isDeleteLocalVariable, parameterName,
                                        replaceAllOccurences, IntroduceParameterRefactoring.REPLACE_FIELDS_WITH_GETTERS_NONE, mustBeFinal,
                                        false, null,
                                        getParamsToRemove(method, occurences)).run();
      } else {
        if (myEditor != null) {
          RefactoringUtil.highlightAllOccurrences(myProject, occurences, myEditor);
        }

        final List<UsageInfo> classMemberRefs = new ArrayList<UsageInfo>();
        if (myExpr != null) {
          Util.analyzeExpression(myExpr, new ArrayList<UsageInfo>(), classMemberRefs, new ArrayList<UsageInfo>());
        }

        final IntroduceParameterDialog dialog =
          new IntroduceParameterDialog(myProject, classMemberRefs, occurences, myLocalVar, myExpr,
                                       createNameSuggestionGenerator(myExpr, propName, myProject, enteredName),
                                       createTypeSelectorManager(occurences, initializerType), methodToSearchFor, method, getParamsToRemove(method, occurences), mustBeFinal);
        dialog.setReplaceAllOccurrences(replaceAllOccurrences);
        dialog.setGenerateDelegate(delegate);
        dialog.show();
        if (myEditor != null) {
          myEditor.getSelectionModel().removeSelection();
        }
      }
    }

    private TypeSelectorManagerImpl createTypeSelectorManager(PsiExpression[] occurences, PsiType initializerType) {
      return myExpr != null ? new TypeSelectorManagerImpl(myProject, initializerType, myExpr, occurences)
                            : new TypeSelectorManagerImpl(myProject, initializerType, occurences);
    }

    private TIntArrayList getParamsToRemove(PsiMethod method, PsiExpression[] occurences) {
      PsiExpression expressionToRemoveParamFrom = myExpr;
      if (myExpr == null) {
        expressionToRemoveParamFrom = myLocalVar.getInitializer();
      }
      return expressionToRemoveParamFrom == null ? new TIntArrayList() : Util
        .findParametersToRemove(method, expressionToRemoveParamFrom, occurences);
    }
  }

  @Override
  public AbstractInplaceIntroducer getInplaceIntroducer() {
    return myInplaceIntroduceParameterPopup;
  }

  @TestOnly
  public boolean introduceStrategy(final Project project, final Editor editor, PsiFile file) {
    final SelectionModel selectionModel = editor.getSelectionModel();
    if (selectionModel.hasSelection()) {
      final PsiElement[] elements = CodeInsightUtil
        .findStatementsInRange(file, selectionModel.getSelectionStart(), selectionModel.getSelectionEnd());
      if (elements.length > 0) {
        final AbstractInplaceIntroducer inplaceIntroducer = AbstractInplaceIntroducer.getActiveIntroducer(editor);
        if (inplaceIntroducer instanceof InplaceIntroduceParameterPopup) {
          return false;
        }
        final List<PsiMethod> enclosingMethods = getEnclosingMethods(Util.getContainingMethod(elements[0]));
        if (enclosingMethods.isEmpty()) {
          return false;
        }

        final MyExtractMethodProcessor processor = new MyExtractMethodProcessor(project, editor, elements);
        try {
          processor.prepare();
          processor.showDialog();

          final PsiMethod emptyMethod = processor.generateEmptyMethod("name");
          final Collection<? extends PsiType> types = FunctionalInterfaceSuggester.suggestFunctionalInterfaces(emptyMethod);
          if (types.isEmpty()) {
            return false;
          }

          if (types.size() == 1 || ApplicationManager.getApplication().isUnitTestMode()) {
            final PsiType next = types.iterator().next();
            functionalInterfaceSelected(next, enclosingMethods, project, editor, processor);
          }
          else {
            final Map<PsiClass, PsiType> classes = new LinkedHashMap<PsiClass, PsiType>();
            for (PsiType type : types) {
              classes.put(PsiUtil.resolveClassInType(type), type);
            }
            final PsiClass[] psiClasses = classes.keySet().toArray(new PsiClass[classes.size()]);
            NavigationUtil.getPsiElementPopup(psiClasses, new PsiClassListCellRenderer(), "Choose From Applicable Functional Interfaces",
                                              new PsiElementProcessor<PsiClass>() {
                                                @Override
                                                public boolean execute(@NotNull PsiClass psiClass) {
                                                  functionalInterfaceSelected(classes.get(psiClass), enclosingMethods, project, editor, processor);
                                                  return true;
                                                }
                                              }).showInBestPositionFor(editor);
            return true;
          }

          return true;
        }
        catch (IncorrectOperationException ignore) {}
        catch (PrepareFailedException ignore) {}
      }
    }
    return false;
  }

  private void functionalInterfaceSelected(final PsiType selectedType,
                                           final List<PsiMethod> enclosingMethods, 
                                           final Project project,
                                           final Editor editor,
                                           final MyExtractMethodProcessor processor) {
    final PairConsumer<PsiMethod, PsiMethod> consumer = new PairConsumer<PsiMethod, PsiMethod>() {
      @Override
      public void consume(PsiMethod methodToIntroduceParameter, PsiMethod methodToSearchFor) {
        introduceWrappedCodeBlockParameter(methodToIntroduceParameter, methodToSearchFor, editor, project, selectedType, processor);
      }
    };
    chooseMethodToIntroduceParameter(editor, enclosingMethods, consumer);
  }

  private void introduceWrappedCodeBlockParameter(PsiMethod methodToIntroduceParameter,
                                                  PsiMethod methodToSearchFor, Editor editor,
                                                  final Project project,
                                                  final PsiType selectedType,
                                                  final MyExtractMethodProcessor processor) {
    final PsiElement[] elements = processor.getElements();
    final PsiElement commonParent = PsiTreeUtil.findCommonParent(elements);
    final RangeMarker marker = editor.getDocument().createRangeMarker(elements[0].getTextOffset(),
                                                                      elements[elements.length - 1].getTextRange().getEndOffset());
    final PsiClass wrapperClass = PsiUtil.resolveClassInType(selectedType);
    LOG.assertTrue(wrapperClass != null);

    final Ref<String> methodCallText = new Ref<String>();
    final Ref<String> methodText = new Ref<String>();
    WriteCommandAction.runWriteCommandAction(project, new Runnable() {
      @Override
      public void run() {
        final PsiMethod method = LambdaUtil.getFunctionalInterfaceMethod(wrapperClass);
        LOG.assertTrue(method != null);
        final String interfaceMethodName = method.getName();
        processor.setMethodName(interfaceMethodName);
        processor.doExtract();

        final PsiMethod extractedMethod = processor.getExtractedMethod();
        methodText.set(extractedMethod.getText());

        final PsiMethodCallExpression methodCall = processor.getMethodCall();
        methodCallText.set(methodCall.getText());

        methodCall.delete();
        extractedMethod.delete();
      }
    });

    final PsiExpression expression = JavaPsiFacade.getElementFactory(project)
      .createExpressionFromText("new " + wrapperClass.getQualifiedName() + "() {" + methodText.get() + "}",
                                methodToIntroduceParameter);
    expression.putUserData(ElementToWorkOn.PARENT, commonParent);
    expression.putUserData(ElementToWorkOn.SUFFIX, "." + methodCallText.get() + ";");

    expression.putUserData(ElementToWorkOn.TEXT_RANGE, marker);
    new Introducer(project, expression, null, editor)
      .introduceParameter(methodToIntroduceParameter, methodToSearchFor);
  }

  private static class MyExtractMethodProcessor extends ExtractMethodProcessor {
    public MyExtractMethodProcessor(Project project, Editor editor, PsiElement[] elements) {
      super(project, editor, elements, null, REFACTORING_NAME, null, null);
    }

    @Override
    protected AbstractExtractDialog createExtractMethodDialog(boolean direct) {
      return new MyAbstractExtractDialog();
    }

    public void setMethodName(String methodName) {
      myMethodName = methodName;
    }

    @Override
    public Boolean hasDuplicates() {
      return false;
    }

    @Override
    protected void deleteExtracted() throws IncorrectOperationException {}

    private class MyAbstractExtractDialog implements AbstractExtractDialog {
      @Override
      public String getChosenMethodName() {
        return "name";
      }

      @Override
      public VariableData[] getChosenParameters() {
        final InputVariables inputVariables = getInputVariables();
        return inputVariables.getInputVariables().toArray(new VariableData[inputVariables.getInputVariables().size()]);
      }

      @Override
      public String getVisibility() {
        return PsiModifier.PUBLIC;
      }

      @Override
      public boolean isMakeStatic() {
        return false;
      }

      @Override
      public boolean isChainedConstructor() {
        return false;
      }

      @Override
      public PsiType getReturnType() {
        return null;
      }

      @Override
      public void show() {}

      @Override
      public boolean isOK() {
        return true;
      }
    }
  }
}
