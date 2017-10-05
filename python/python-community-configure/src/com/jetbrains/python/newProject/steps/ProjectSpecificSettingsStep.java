/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.jetbrains.python.newProject.steps;

import com.google.common.collect.Iterables;
import com.intellij.execution.ExecutionException;
import com.intellij.facet.ui.ValidationResult;
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep;
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase;
import com.intellij.ide.util.projectWizard.WebProjectTemplate;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.DirectoryProjectGenerator;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.HideableDecorator;
import com.intellij.ui.TextAccessor;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.PathUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.UiNotifyConnector;
import com.jetbrains.python.configuration.PyConfigurableInterpreterList;
import com.jetbrains.python.configuration.VirtualEnvProjectFilter;
import com.jetbrains.python.newProject.PyFrameworkProjectGenerator;
import com.jetbrains.python.newProject.PythonProjectGenerator;
import com.jetbrains.python.packaging.PyPackage;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.packaging.PyPackageUtil;
import com.jetbrains.python.remote.PyProjectSynchronizer;
import com.jetbrains.python.remote.PythonRemoteInterpreterManager;
import com.jetbrains.python.sdk.PreferredSdkComparator;
import com.jetbrains.python.sdk.PyLazySdk;
import com.jetbrains.python.sdk.PySdkUtil;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.add.PyAddNewVirtualEnvPanel;
import icons.PythonIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

public class ProjectSpecificSettingsStep<T> extends ProjectSettingsStepBase<T> implements DumbAware {
  private static final Logger LOGGER = Logger.getInstance(ProjectSpecificSettingsStep.class);
  private PythonSdkChooserCombo mySdkCombo;
  private boolean myInstallFramework;
  private Sdk mySdk;
  @Nullable private JPanel mySelectedInterpretersPanel;
  @Nullable private PyAddNewVirtualEnvPanel myNewVirtualEnvPanel;

  /**
   * For remote projects path for project on remote side
   */
  private PyRemotePathField myRemotePathField;
  /**
   * If remote path required for project creation or not
   */
  private boolean myRemotePathRequired;

  public ProjectSpecificSettingsStep(@NotNull final DirectoryProjectGenerator<T> projectGenerator,
                                     @NotNull final AbstractNewProjectStep.AbstractCallback callback) {
    super(projectGenerator, callback);
  }

  private static void acceptsSdk(@NotNull final DirectoryProjectGenerator<?> generator,
                                 @NotNull final Sdk sdk,
                                 @NotNull final File projectDirectory) throws PythonProjectGenerator.PyNoProjectAllowedOnSdkException {
    if (generator instanceof PythonProjectGenerator) {
      ((PythonProjectGenerator<?>)generator).checkProjectCanBeCreatedOnSdk(sdk, projectDirectory);
    }
  }

  @Override
  protected JPanel createAndFillContentPanel() {
    if (myProjectGenerator instanceof PythonProjectGenerator) {
      // Allow generator to display custom error
      ((PythonProjectGenerator<?>)myProjectGenerator).setErrorCallback(this::setErrorText);
    }
    return createContentPanelWithAdvancedSettingsPanel();
  }

  @Override
  @Nullable
  protected JPanel createAdvancedSettings() {
    JComponent advancedSettings = null;
    if (myProjectGenerator instanceof PythonProjectGenerator) {
      advancedSettings = ((PythonProjectGenerator)myProjectGenerator).getSettingsPanel(myProjectDirectory);
    }
    else if (myProjectGenerator instanceof WebProjectTemplate) {
      advancedSettings = getPeer().getComponent();
    }
    if (advancedSettings != null) {
      final JPanel jPanel = new JPanel(new VerticalFlowLayout());
      final HideableDecorator deco = new HideableDecorator(jPanel, "Mor&e Settings", false);
      boolean isValid = checkValid();
      deco.setOn(!isValid);
      if (myProjectGenerator instanceof PythonProjectGenerator && !deco.isExpanded()) {
        final ValidationResult result = ((PythonProjectGenerator)myProjectGenerator).warningValidation(getSdk());
        deco.setOn(!result.isOk());
      }
      deco.setContentComponent(advancedSettings);
      return jPanel;
    }
    return null;
  }

  @Nullable
  public Sdk getSdk() {
    if (!(myProjectGenerator instanceof PythonProjectGenerator)) return null;
    if (mySdk != null) return mySdk;
    if (mySelectedInterpretersPanel != null && mySelectedInterpretersPanel == myNewVirtualEnvPanel) {
      return new PyLazySdk("Uninitialized virtual environment at " + myNewVirtualEnvPanel.getPath(),
                           () -> myNewVirtualEnvPanel != null ? myNewVirtualEnvPanel.getOrCreateSdk() : null);
    }
    return (Sdk)mySdkCombo.getComboBox().getSelectedItem();
  }

  public void setSdk(final Sdk sdk) {
    mySdk = sdk;
  }

  public boolean installFramework() {
    return myInstallFramework;
  }

  @Override
  protected void registerValidators() {
    super.registerValidators();
    if (myProjectGenerator instanceof PythonProjectGenerator) {
      myLocationField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(DocumentEvent e) {
          final String path = myLocationField.getText().trim();
          ((PythonProjectGenerator)myProjectGenerator).locationChanged(PathUtil.getFileName(path));
        }
      });

      mySdkCombo.getComboBox().addItemListener(e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) {
          final Runnable checkValidOnSwing = () -> ApplicationManager.getApplication().invokeLater(this::checkValid);
          ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try {
              // Refresh before validation to make sure no stale data
              final Sdk sdk = getSdk();
              if (sdk == null) {
                return;
              }
              final boolean noPackages = PyPackageManager.getInstance(sdk).refreshAndGetPackages(true).isEmpty();
              if (noPackages) {
                LOGGER.warn(String.format("No packages on %s", sdk.getHomePath()));
              }
              checkValidOnSwing.run();
            }
            catch (final ExecutionException exception) {
              LOGGER.warn(exception);
              checkValidOnSwing.run();
            }
          }, "Refreshing List of Packages, Please Wait", false, null);
        }
      });

      if (myRemotePathField != null) {
        myRemotePathField.addTextChangeListener(this::checkValid);
      }

      if (myNewVirtualEnvPanel != null) {
        myNewVirtualEnvPanel.addChangeListener(this::checkValid);
      }
      
      UiNotifyConnector.doWhenFirstShown(mySdkCombo, this::checkValid);
    }
  }

  /**
   * @return path for project on remote side provided by user
   */
  @Nullable
  final String getRemotePath() {
    final PyRemotePathField field = myRemotePathField;
    return (field != null ? field.getTextField().getText() : null);
  }

  @Override
  protected void initGeneratorListeners() {
    super.initGeneratorListeners();
    if (myProjectGenerator instanceof PythonProjectGenerator) {
      ((PythonProjectGenerator)myProjectGenerator).addSettingsStateListener(this::checkValid);
      myErrorLabel.addMouseListener(((PythonProjectGenerator)myProjectGenerator).getErrorLabelMouseListener());
    }
  }

  @Override
  public boolean checkValid() {
    myInstallFramework = false;
    if (!super.checkValid()) {
      return false;
    }

    if (myNewVirtualEnvPanel != null) {
      final List<ValidationInfo> validationInfos = myNewVirtualEnvPanel.validateAll();
      if (!validationInfos.isEmpty()) {
        setErrorText(StringUtil.join(validationInfos, info -> info.message, "\n"));
        return false;
      }
    }

    if (myProjectGenerator instanceof PythonProjectGenerator) {
      final Sdk sdk = getSdk();
      if (sdk == null) {
        setErrorText("No Python interpreter selected");
        return false;
      }
      if (!(sdk instanceof PyLazySdk) && PythonSdkType.isInvalid(sdk)) {
        setErrorText("Choose valid python interpreter");
        return false;
      }
      final List<String> warningList = new ArrayList<>();
      final boolean isPy3k = PythonSdkType.getLanguageLevelForSdk(sdk).isPy3K();
      try {
        acceptsSdk(myProjectGenerator, sdk, new File(myLocationField.getText()));
      }
      catch (final PythonProjectGenerator.PyNoProjectAllowedOnSdkException e) {
        setErrorText(e.getMessage());
        return false;
      }
      if (myRemotePathRequired && StringUtil.isEmpty(myRemotePathField.getTextField().getText())) {
        setErrorText("Remote path not provided");
        return false;
      }

      if (myProjectGenerator instanceof PyFrameworkProjectGenerator) {
        PyFrameworkProjectGenerator frameworkProjectGenerator = (PyFrameworkProjectGenerator)myProjectGenerator;
        String frameworkName = frameworkProjectGenerator.getFrameworkTitle();

        if (isPy3k && !((PyFrameworkProjectGenerator)myProjectGenerator).supportsPython3()) {
          setErrorText(frameworkName + " is not supported for the selected interpreter");
          return false;
        }

        if (PythonSdkType.isRemote(sdk)) {
          return true;
        }
        // All code beyond this line may be heavy in case of remote sdk and should not be called on AWT
        // pretend everything is ok for remote and check package later

        if (!isFrameworkInstalled(sdk)) {
          if (PyPackageUtil.packageManagementEnabled(sdk)) {
            myInstallFramework = true;
            final List<PyPackage> packages = PyPackageUtil.refreshAndGetPackagesModally(sdk);
            if (!PyPackageUtil.hasManagement(packages)) {
              warningList.add("Python packaging tools and " + frameworkName + " will be installed on the selected interpreter");
            }
            else {
              warningList.add(frameworkName + " will be installed on the selected interpreter");
            }
          }
          else {
            warningList.add(frameworkName + " is not installed on the selected interpreter");
          }
        }
        final ValidationResult warningResult = ((PythonProjectGenerator)myProjectGenerator).warningValidation(sdk);
        if (!warningResult.isOk()) {
          warningList.add(warningResult.getErrorMessage());
        }

        if (!warningList.isEmpty()) {
          final String warning = StringUtil.join(warningList, "<br/>");
          setWarningText(warning);
        }
      }
    }
    return true;
  }

  private boolean isFrameworkInstalled(Sdk sdk) {
    PyFrameworkProjectGenerator projectGenerator = (PyFrameworkProjectGenerator)getProjectGenerator();
    return projectGenerator != null && projectGenerator.isFrameworkInstalled(sdk);
  }

  @Override
  protected JPanel createBasePanel() {
    if (myProjectGenerator instanceof PythonProjectGenerator) {
      final BorderLayout layout = new BorderLayout();

      final JPanel locationPanel = new JPanel(layout);

      final JPanel panel = new JPanel(new VerticalFlowLayout(0, 2));
      final LabeledComponent<TextFieldWithBrowseButton> location = createLocationComponent();

      locationPanel.add(location, BorderLayout.CENTER);
      panel.add(locationPanel);
      panel.add(createInterpretersPanel());

      final PythonRemoteInterpreterManager remoteInterpreterManager = PythonRemoteInterpreterManager.getInstance();

      final Sdk sdk = getSdk();
      if (remoteInterpreterManager != null && sdk != null) {
        createRemotePathField(panel, remoteInterpreterManager);
      }


      final JPanel basePanelExtension = ((PythonProjectGenerator)myProjectGenerator).extendBasePanel();
      if (basePanelExtension != null) {
        panel.add(basePanelExtension);
      }
      return panel;
    }

    return super.createBasePanel();
  }

  @NotNull
  private JPanel createInterpretersPanel() {
    final JPanel container = new JPanel(new BorderLayout());
    final JPanel decoratorPanel = new JPanel(new VerticalFlowLayout());
    final HideableDecorator decorator = new HideableDecorator(decoratorPanel, "Project Interpreter", false);
    decorator.setContentComponent(container);
    final Project defaultProject = ProjectManager.getInstance().getDefaultProject();
    final List<Sdk> pythonSdks = PyConfigurableInterpreterList.getInstance(defaultProject).getAllPythonSdks();
    Iterables.removeIf(pythonSdks, sdk -> !(sdk.getSdkType() instanceof PythonSdkType) || PythonSdkType.isInvalid(sdk));
    Collections.sort(pythonSdks, new PreferredSdkComparator());
    myNewVirtualEnvPanel = new PyAddNewVirtualEnvPanel(null, pythonSdks, myLocationField.getText().trim());
    myLocationField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        myNewVirtualEnvPanel.setNewProjectPath(myLocationField.getText().trim());
      }
    });

    final Map<JBRadioButton, JPanel> radioButtons = new LinkedHashMap<>();
    final JBRadioButton newVirtualEnvButton = new JBRadioButton(myNewVirtualEnvPanel.getPanelName());
    radioButtons.put(newVirtualEnvButton, myNewVirtualEnvPanel);
    radioButtons.put(new JBRadioButton("Existing interpreter"), createInterpreterCombo());
    final ButtonGroup buttonGroup = new ButtonGroup();
    for (JBRadioButton button : radioButtons.keySet()) {
      buttonGroup.add(button);
    }

    final FormBuilder formBuilder = FormBuilder.createFormBuilder();
    for (Map.Entry<JBRadioButton, JPanel> entry : radioButtons.entrySet()) {
      final JBRadioButton button = entry.getKey();
      final JPanel panel = entry.getValue();
      panel.setBorder(JBUI.Borders.emptyLeft(30));
      formBuilder.addComponent(button);
      formBuilder.addComponent(panel);
      button.addItemListener(e -> {
        mySelectedInterpretersPanel = panel;
        for (JPanel p : radioButtons.values()) {
          final boolean isCurrentPanel = p.equals(panel);
          UIUtil.setEnabled(p, isCurrentPanel, true);
          if (isCurrentPanel) {
            decorator.setTitle("Project Interpreter: " + StringUtil.toTitleCase(button.getText()));
          }
        }
      });
    }

    mySelectedInterpretersPanel = myNewVirtualEnvPanel;
    newVirtualEnvButton.setSelected(true);

    container.add(formBuilder.getPanel(), BorderLayout.NORTH);

    return decoratorPanel;
  }

  private void createRemotePathField(@NotNull final JPanel panelToAddField,
                                     @NotNull final PythonRemoteInterpreterManager remoteInterpreterManager) {
    myRemotePathField = new PyRemotePathField();

    myRemotePathField.addActionListener(e -> {
      final Sdk currentSdk = getSdk();
      if (!PySdkUtil.isRemote(currentSdk)) {
        return;
      }
      // If chosen SDK is remote then display

      final Pair<Supplier<String>, JPanel> browserForm;
      try {
        browserForm = remoteInterpreterManager.createServerBrowserForm(currentSdk);
      }
      catch (final ExecutionException  | InterruptedException ex) {
        Logger.getInstance(ProjectSpecificSettingsStep.class).warn("Failed to create server browse button", ex);
        JBPopupFactory.getInstance().createMessage("Failed to browse remote server. Make sure you have permissions. ").show(panelToAddField);
        return;
      }
      if (browserForm != null) {
        browserForm.second.setVisible(true);
        final DialogWrapper wrapper = new MyRemoteServerBrowserDialog(browserForm.second);
        if (wrapper.showAndGet()) {
          myRemotePathField.getTextField().setText(browserForm.first.get());
        }
      }
    });

    mySdkCombo.addChangedListener(e -> configureMappingField(remoteInterpreterManager));
    panelToAddField.add(myRemotePathField.getMainPanel());
    configureMappingField(remoteInterpreterManager);
  }

  /**
   * Enables or disables "remote path" based on interpreter.
   */
  private void configureMappingField(@NotNull final PythonRemoteInterpreterManager remoteInterpreterManager) {
    if (myRemotePathField == null) {
      return;
    }

    final JPanel mainPanel = myRemotePathField.getMainPanel();
    final PyProjectSynchronizer synchronizer = getSynchronizer(remoteInterpreterManager);
    if (synchronizer != null) {
      final String defaultRemotePath = synchronizer.getDefaultRemotePath();
      final boolean mappingRequired = defaultRemotePath != null;
      mainPanel.setVisible(mappingRequired);
      final TextAccessor textField = myRemotePathField.getTextField();
      if (mappingRequired && StringUtil.isEmpty(textField.getText())) {
        textField.setText(defaultRemotePath);
      }
      myRemotePathRequired = mappingRequired;
    }
    else {
      mainPanel.setVisible(false);
      myRemotePathRequired = false;
    }
  }

  @Nullable
  private PyProjectSynchronizer getSynchronizer(@NotNull final PythonRemoteInterpreterManager manager) {
    final Sdk sdk = getSdk();
    if (sdk == null) {
      return null;
    }
    return manager.getSynchronizer(sdk);
  }

  @NotNull
  private PythonSdkChooserCombo createInterpreterCombo() {
    final Project project = ProjectManager.getInstance().getDefaultProject();
    final List<Sdk> sdks = PyConfigurableInterpreterList.getInstance(project).getAllPythonSdks();
    VirtualEnvProjectFilter.removeAllAssociated(sdks);
    Iterables.removeIf(sdks, sdk -> sdk != null && PythonSdkType.isInvalid(sdk));
    Sdk compatibleSdk = sdks.isEmpty() ? null : sdks.iterator().next();
    DirectoryProjectGenerator generator = getProjectGenerator();
    if (generator instanceof PyFrameworkProjectGenerator && !((PyFrameworkProjectGenerator)generator).supportsPython3()) {
      if (compatibleSdk != null && PythonSdkType.getLanguageLevelForSdk(compatibleSdk).isPy3K()) {
        Sdk python2Sdk = PythonSdkType.findPython2Sdk(sdks);
        if (python2Sdk != null) {
          compatibleSdk = python2Sdk;
        }
      }
    }

    final Sdk preferred = compatibleSdk;
    mySdkCombo = new PythonSdkChooserCombo(project, sdks, myLocationField.getText().trim(), sdk -> sdk == preferred);
    if (SystemInfo.isMac && !UIUtil.isUnderDarcula()) {
      mySdkCombo.putClientProperty("JButton.buttonType", null);
    }
    mySdkCombo.setButtonIcon(PythonIcons.Python.InterpreterGear);
    myLocationField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(DocumentEvent e) {
        mySdkCombo.setNewProjectPath(myLocationField.getText().trim());
      }
    });

    return mySdkCombo;
  }

  /**
   * Dialog to display remote server browser
   */
  private static class MyRemoteServerBrowserDialog extends DialogWrapper {

    private final JPanel myBrowserForm;

    MyRemoteServerBrowserDialog(@NotNull final JPanel browserForm) {
      super(true);
      myBrowserForm = browserForm;
      init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
      return myBrowserForm;
    }
  }
}
