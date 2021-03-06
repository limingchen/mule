/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.dsl.model;

import static com.google.common.base.Joiner.on;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.collections.CollectionUtils.disjunction;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mule.runtime.api.component.ComponentIdentifier.builder;
import static org.mule.runtime.api.component.TypedComponentIdentifier.ComponentType.UNKNOWN;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.util.Preconditions.checkState;
import static org.mule.runtime.config.spring.dsl.processor.xml.XmlCustomAttributeHandler.from;
import static org.mule.runtime.config.spring.dsl.spring.BeanDefinitionFactory.SOURCE_TYPE;
import static org.mule.runtime.core.api.exception.Errors.Identifiers.ANY_IDENTIFIER;
import static org.mule.runtime.extension.api.util.NameUtils.hyphenize;
import static org.mule.runtime.extension.api.util.NameUtils.pluralize;
import static org.mule.runtime.internal.dsl.DslConstants.CORE_PREFIX;
import static org.mule.runtime.internal.dsl.DslConstants.DOMAIN_PREFIX;
import static org.mule.runtime.internal.dsl.DslConstants.EE_DOMAIN_PREFIX;

import org.mule.runtime.api.app.declaration.ArtifactDeclaration;
import org.mule.runtime.api.app.declaration.ElementDeclaration;
import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.component.ConfigurationProperties;
import org.mule.runtime.api.component.TypedComponentIdentifier;
import org.mule.runtime.api.dsl.DslResolvingContext;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.meta.AbstractAnnotatedObject;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.config.spring.dsl.model.extension.xml.MacroExpansionModuleModel;
import org.mule.runtime.config.spring.dsl.processor.ArtifactConfig;
import org.mule.runtime.config.spring.dsl.processor.ConfigFile;
import org.mule.runtime.config.spring.dsl.processor.ConfigLine;
import org.mule.runtime.config.spring.dsl.processor.ObjectTypeVisitor;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.config.RuntimeConfigurationException;
import org.mule.runtime.core.component.config.CompositeConfigurationPropertiesProvider;
import org.mule.runtime.core.component.config.ConfigurationPropertiesComponent;
import org.mule.runtime.core.component.config.ConfigurationPropertiesProvider;
import org.mule.runtime.core.component.config.ConfigurationPropertiesResolver;
import org.mule.runtime.core.component.config.ConfigurationProperty;
import org.mule.runtime.core.component.config.DefaultConfigurationPropertiesResolver;
import org.mule.runtime.core.component.config.FileConfigurationPropertiesProvider;
import org.mule.runtime.core.component.config.GlobalPropertyConfigurationPropertiesProvider;
import org.mule.runtime.core.component.config.MapConfigurationPropertiesProvider;
import org.mule.runtime.core.component.config.PropertiesResolverConfigurationProperties;
import org.mule.runtime.core.component.config.ResourceProvider;
import org.mule.runtime.core.component.config.SystemPropertiesConfigurationProvider;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinition;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinitionProvider;
import org.mule.runtime.dsl.api.component.config.ComponentConfiguration;
import org.mule.runtime.dsl.api.component.config.DefaultComponentLocation;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import org.apache.commons.lang3.ClassUtils;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.w3c.dom.Node;

/**
 * An {@code ApplicationModel} holds a representation of all the artifact configuration using an abstract model to represent any
 * configuration option.
 * <p/>
 * This model is represented by a set of {@link org.mule.runtime.config.spring.dsl.model.ComponentModel}. Each
 * {@code ComponentModel} holds a piece of configuration and may have children {@code ComponentModel}s as defined in the artifact
 * configuration.
 * <p/>
 * Once the set of {@code ComponentModel} gets created from the application
 * {@link org.mule.runtime.config.spring.dsl.processor.ConfigFile}s the {@code ApplicationModel} executes a set of common
 * validations dictated by the configuration semantics.
 *
 * @since 4.0
 */
public class ApplicationModel {

  // TODO MULE-9692 move this logic elsewhere. This are here just for the language rules and those should be processed elsewhere.
  public static final String MULE_ROOT_ELEMENT = "mule";
  public static final String MULE_DOMAIN_ROOT_ELEMENT = "mule-domain";
  public static final String IMPORT_ELEMENT = "import";
  public static final String POLICY_ROOT_ELEMENT = "policy";
  public static final String ANNOTATIONS = "annotations";
  public static final String ERROR_HANDLER = "error-handler";
  public static final String ERROR_MAPPING = "error-mapping";
  public static final String MAX_REDELIVERY_ATTEMPTS_ROLLBACK_ES_ATTRIBUTE = "maxRedeliveryAttempts";
  public static final String WHEN_CHOICE_ES_ATTRIBUTE = "when";
  public static final String TYPE_ES_ATTRIBUTE = "type";
  public static final String EXCEPTION_STRATEGY_REFERENCE_ELEMENT = "exception-strategy";
  public static final String PROPERTY_ELEMENT = "property";
  public static final String NAME_ATTRIBUTE = "name";
  public static final String REFERENCE_ATTRIBUTE = "ref";
  public static final String VALUE_ATTRIBUTE = "value";
  public static final String PROCESSOR_REFERENCE_ELEMENT = "processor";
  public static final String TRANSFORMER_REFERENCE_ELEMENT = "transformer";
  public static final String ANNOTATION_ELEMENT = "annotations";
  public static final String CONFIGURATION_ELEMENT = "configuration";
  public static final String DATA_WEAVE = "weave";
  public static final String CUSTOM_TRANSFORMER = "custom-transformer";
  public static final String DESCRIPTION_ELEMENT = "description";
  public static final String PROPERTIES_ELEMENT = "properties";
  public static final String FLOW_ELEMENT = "flow";
  public static final String FLOW_REF_ELEMENT = "flow-ref";
  public static final String SUBFLOW_ELEMENT = "sub-flow";
  private static final String MODULE_OPERATION_CHAIN_ELEMENT = "module-operation-chain";

  public static final String REDELIVERY_POLICY_ELEMENT = "redelivery-policy";
  // TODO MULE-9638 Remove once all bean definitions parsers where migrated
  public static final String TEST_NAMESPACE = "test";
  public static final String DOC_NAMESPACE = "doc";
  public static final String SPRING_SECURITY_NAMESPACE = "ss";
  public static final String MULE_SECURITY_NAMESPACE = "mule-ss";
  public static final String MULE_XML_NAMESPACE = "mulexml";
  public static final String PGP_NAMESPACE = "pgp";
  public static final String XSL_NAMESPACE = "xsl";
  public static final String TRANSPORT_NAMESPACE = "transports";
  public static final String JMS_NAMESPACE = "jms";
  public static final String VM_NAMESPACE = "vm";
  public static final String HTTP_TRANSPORT_NAMESPACE = "http-transport";
  public static final String BATCH_NAMESPACE = "batch";
  public static final String PARSER_TEST_NAMESPACE = "parsers-test";
  public static final String GLOBAL_PROPERTY = "global-property";
  public static final String SECURITY_MANAGER = "security-manager";
  public static final String CONFIGURATION_PROPERTIES_ELEMENT = "configuration-properties";
  public static final String OBJECT_ELEMENT = "object";

  public static final ComponentIdentifier ERROR_HANDLER_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(ERROR_HANDLER).build();
  public static final ComponentIdentifier EXCEPTION_STRATEGY_REFERENCE_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(EXCEPTION_STRATEGY_REFERENCE_ELEMENT)
          .build();
  public static final ComponentIdentifier ERROR_MAPPING_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(ERROR_MAPPING).build();
  public static final ComponentIdentifier MULE_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(MULE_ROOT_ELEMENT).build();
  public static final ComponentIdentifier MULE_DOMAIN_IDENTIFIER =
      builder().withNamespace(DOMAIN_PREFIX).withName(MULE_DOMAIN_ROOT_ELEMENT).build();
  public static final ComponentIdentifier MULE_EE_DOMAIN_IDENTIFIER =
      builder().withNamespace(EE_DOMAIN_PREFIX).withName(MULE_DOMAIN_ROOT_ELEMENT).build();
  public static final ComponentIdentifier POLICY_IDENTIFIER =
      builder().withNamespace(POLICY_ROOT_ELEMENT).withName(POLICY_ROOT_ELEMENT).build();
  public static final ComponentIdentifier MULE_PROPERTY_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(PROPERTY_ELEMENT).build();
  public static final ComponentIdentifier MULE_PROPERTIES_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(PROPERTIES_ELEMENT).build();
  public static final ComponentIdentifier ANNOTATIONS_ELEMENT_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(ANNOTATION_ELEMENT).build();
  public static final ComponentIdentifier PROCESSOR_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(PROCESSOR_REFERENCE_ELEMENT).build();
  public static final ComponentIdentifier TRANSFORMER_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(TRANSFORMER_REFERENCE_ELEMENT).build();
  public static final ComponentIdentifier CONFIGURATION_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(CONFIGURATION_ELEMENT).build();
  public static final ComponentIdentifier CUSTOM_TRANSFORMER_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(CUSTOM_TRANSFORMER).build();
  public static final ComponentIdentifier DOC_DESCRIPTION_IDENTIFIER =
      builder().withNamespace(DOC_NAMESPACE).withName(DESCRIPTION_ELEMENT).build();
  public static final ComponentIdentifier DESCRIPTION_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(DESCRIPTION_ELEMENT).build();
  public static final ComponentIdentifier ANNOTATIONS_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(ANNOTATIONS).build();
  public static final ComponentIdentifier OBJECT_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(OBJECT_ELEMENT).build();
  public static final ComponentIdentifier FLOW_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(FLOW_ELEMENT).build();
  public static final ComponentIdentifier FLOW_REF_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(FLOW_REF_ELEMENT).build();
  public static final ComponentIdentifier SUBFLOW_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(SUBFLOW_ELEMENT).build();
  public static final ComponentIdentifier REDELIVERY_POLICY_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(REDELIVERY_POLICY_ELEMENT).build();
  public static final ComponentIdentifier GLOBAL_PROPERTY_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(GLOBAL_PROPERTY).build();
  public static final ComponentIdentifier SECURITY_MANAGER_IDENTIFIER =
      builder().withNamespace(CORE_PREFIX).withName(SECURITY_MANAGER).build();
  public static final ComponentIdentifier CONFIGURATION_PROPERTIES =
      builder().withNamespace(CORE_PREFIX).withName(CONFIGURATION_PROPERTIES_ELEMENT).build();
  public static final ComponentIdentifier MODULE_OPERATION_CHAIN =
      builder().withNamespace(CORE_PREFIX).withName(MODULE_OPERATION_CHAIN_ELEMENT).build();


  // TODO MULE-13042 - remove this constants and their usages one this code gets migrated to use extension models.
  public static final String MUNIT_PREFIX = "munit";
  public static final ComponentIdentifier MUNIT_TEST_IDENTIFIER =
      builder().withNamespace(MUNIT_PREFIX).withName("test").build();
  public static final ComponentIdentifier MUNIT_BEFORE_TEST_IDENTIFIER =
      builder().withNamespace(MUNIT_PREFIX).withName("before-test").build();
  public static final ComponentIdentifier MUNIT_BEFORE_SUITE_IDENTIFIER =
      builder().withNamespace(MUNIT_PREFIX).withName("before-suite").build();
  public static final ComponentIdentifier MUNIT_AFTER_TEST_IDENTIFIER =
      builder().withNamespace(MUNIT_PREFIX).withName("after-test").build();
  public static final ComponentIdentifier MUNIT_AFTER_SUITE_IDENTIFIER =
      builder().withNamespace(MUNIT_PREFIX).withName("after-suite").build();

  public static final String CLASS_ATTRIBUTE = "class";

  private static ImmutableSet<ComponentIdentifier> ignoredNameValidationComponentList =
      ImmutableSet.<ComponentIdentifier>builder()
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("flow-ref").build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("alias").build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("password-encryption-strategy")
              .build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("custom-security-provider")
              .build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("custom-encryption-strategy")
              .build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT)
              .withName("secret-key-encryption-strategy")
              .build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("import").build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT)
              .withName("string-to-byte-array-transformer")
              .build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("append-string-transformer")
              .build())
          .add(builder().withNamespace(MULE_ROOT_ELEMENT).withName("security-manager").build())
          .add(builder().withNamespace(TEST_NAMESPACE).withName("queue").build())
          .add(builder().withNamespace(TEST_NAMESPACE).withName("invocation-counter").build())
          .add(builder().withNamespace(SPRING_SECURITY_NAMESPACE).withName("user").build())
          .add(builder().withNamespace(MULE_SECURITY_NAMESPACE)
              .withName("delegate-security-provider")
              .build())
          .add(builder().withNamespace(MULE_SECURITY_NAMESPACE).withName("security-manager")
              .build())
          .add(builder().withNamespace(MULE_XML_NAMESPACE).withName("xslt-transformer").build())
          .add(builder().withNamespace(MULE_XML_NAMESPACE).withName("alias").build())
          .add(builder().withNamespace(PGP_NAMESPACE).withName("security-provider").build())
          .add(builder().withNamespace(PGP_NAMESPACE).withName("keybased-encryption-strategy")
              .build())
          .add(builder().withNamespace(XSL_NAMESPACE).withName("param").build())
          .add(builder().withNamespace(XSL_NAMESPACE).withName("attribute").build())
          .add(builder().withNamespace(XSL_NAMESPACE).withName("element").build())
          .add(builder().withNamespace(TRANSPORT_NAMESPACE).withName("inbound-endpoint").build())
          .add(builder().withNamespace(TRANSPORT_NAMESPACE).withName("outbound-endpoint").build())
          .add(builder().withNamespace(JMS_NAMESPACE).withName("inbound-endpoint").build())
          .add(builder().withNamespace(VM_NAMESPACE).withName("inbound-endpoint").build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE).withName("inbound-endpoint").build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE).withName("set-cookie").build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE).withName("header").build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE)
              .withName("http-response-to-object-transformer")
              .build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE)
              .withName("http-response-to-string-transformer")
              .build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE)
              .withName("message-to-http-response-transformer")
              .build())
          .add(builder().withNamespace(HTTP_TRANSPORT_NAMESPACE)
              .withName("object-to-http-request-transformer")
              .build())
          .add(builder().withNamespace(BATCH_NAMESPACE).withName("step").build())
          .add(builder().withNamespace(BATCH_NAMESPACE).withName("execute").build())
          .add(builder().withNamespace(PARSER_TEST_NAMESPACE).withName("child").build())
          .add(builder().withNamespace(PARSER_TEST_NAMESPACE).withName("kid").build())
          .add(builder().withNamespace(DATA_WEAVE).withName("reader-property").build())
          .build();

  private final Optional<ComponentBuildingDefinitionRegistry> componentBuildingDefinitionRegistry;
  private List<ComponentModel> muleComponentModels = new LinkedList<>();
  private PropertiesResolverConfigurationProperties configurationProperties;
  private ResourceProvider externalResourceProvider;

  /**
   * Creates an {code ApplicationModel} from a {@link ArtifactConfig}.
   * <p/>
   * A set of validations are applied that may make creation fail.
   *
   * @param artifactConfig the mule artifact configuration content.
   * @param artifactDeclaration an {@link ArtifactDeclaration}
   * @throws Exception when the application configuration has semantic errors.
   */
  public ApplicationModel(ArtifactConfig artifactConfig, ArtifactDeclaration artifactDeclaration,
                          ResourceProvider externalResourceProvider)
      throws Exception {
    this(artifactConfig, artifactDeclaration, emptySet(), emptyMap(), empty(), of(new ComponentBuildingDefinitionRegistry()),
         true, externalResourceProvider);
  }

  /**
   * Creates an {code ApplicationModel} from a {@link ArtifactConfig}.
   * <p/>
   * A set of validations are applied that may make creation fail.
   *
   * @param artifactConfig the mule artifact configuration content.
   * @param artifactDeclaration an {@link ArtifactDeclaration}
   * @param extensionModels Set of {@link ExtensionModel extensionModels} that will be used to type componentModels
   * @param parentConfigurationProperties the {@link ConfigurablePropertyResolver} of the parent artifact. For instance,
   *        application will receive the domain resolver.
   * @param componentBuildingDefinitionRegistry an optional {@link ComponentBuildingDefinitionRegistry} used to correlate items in
   *        this model to their definitions
   * @param runtimeMode true implies the mule application should behave as a runtime app (e.g.: smart connectors will be macro
   *        expanded) false implies the mule is being created from a tooling perspective.
   * @throws Exception when the application configuration has semantic errors.
   */
  // TODO: MULE-9638 remove this optional
  public ApplicationModel(ArtifactConfig artifactConfig, ArtifactDeclaration artifactDeclaration,
                          Set<ExtensionModel> extensionModels,
                          Map<String, String> deploymentProperties,
                          Optional<ConfigurationProperties> parentConfigurationProperties,
                          Optional<ComponentBuildingDefinitionRegistry> componentBuildingDefinitionRegistry,
                          boolean runtimeMode, ResourceProvider externalResourceProvider)
      throws Exception {

    this.componentBuildingDefinitionRegistry = componentBuildingDefinitionRegistry;
    this.externalResourceProvider = externalResourceProvider;
    createConfigurationAttributeResolver(artifactConfig, parentConfigurationProperties, deploymentProperties);
    convertConfigFileToComponentModel(artifactConfig);
    convertArtifactDeclarationToComponentModel(extensionModels, artifactDeclaration);
    validateModel(componentBuildingDefinitionRegistry);
    createEffectiveModel();
    if (runtimeMode) {
      expandModules(extensionModels);
    }
    resolveComponentTypes();
    executeOnEveryMuleComponentTree(new ComponentLocationVisitor());
  }

  private void createConfigurationAttributeResolver(ArtifactConfig artifactConfig,
                                                    Optional<ConfigurationProperties> parentConfigurationProperties,
                                                    Map<String, String> deploymentProperties) {

    SystemPropertiesConfigurationProvider systemPropertiesConfigurationProvider = new SystemPropertiesConfigurationProvider();
    DefaultConfigurationPropertiesResolver localResolver =
        new DefaultConfigurationPropertiesResolver(empty(), systemPropertiesConfigurationProvider);
    List<ConfigurationPropertiesProvider> configConfigurationPropertiesProviders =
        getConfigurationPropertiesProvidersFromComponents(artifactConfig, localResolver);
    FileConfigurationPropertiesProvider externalPropertiesConfigurationProvider =
        new FileConfigurationPropertiesProvider(externalResourceProvider, "External files");

    Optional<ConfigurationPropertiesResolver> parentConfigurationPropertiesResolver = of(localResolver);
    if (parentConfigurationProperties.isPresent()) {
      parentConfigurationPropertiesResolver =
          of(new DefaultConfigurationPropertiesResolver(empty(), new ConfigurationPropertiesProvider() {

            @Override
            public Optional<ConfigurationProperty> getConfigurationProperty(String configurationAttributeKey) {
              return parentConfigurationProperties.get().resolveProperty(configurationAttributeKey)
                  .map(value -> new ConfigurationProperty(parentConfigurationProperties, configurationAttributeKey, value));
            }

            @Override
            public String getDescription() {
              return "Domain properties";
            }
          }));
    }

    if (!configConfigurationPropertiesProviders.isEmpty()) {
      CompositeConfigurationPropertiesProvider configurationAttributesProvider =
          new CompositeConfigurationPropertiesProvider(configConfigurationPropertiesProviders);
      parentConfigurationPropertiesResolver = of(new DefaultConfigurationPropertiesResolver(parentConfigurationPropertiesResolver,
                                                                                            configurationAttributesProvider));

    }
    ConfigurationPropertiesProvider globalPropertiesConfigurationAttributeProvider =
        createProviderFromGlobalProperties(artifactConfig);
    DefaultConfigurationPropertiesResolver globalPropertiesConfigurationPropertiesResolver =
        new DefaultConfigurationPropertiesResolver(parentConfigurationPropertiesResolver,
                                                   globalPropertiesConfigurationAttributeProvider);
    DefaultConfigurationPropertiesResolver systemPropertiesResolver =
        new DefaultConfigurationPropertiesResolver(of(globalPropertiesConfigurationPropertiesResolver),
                                                   systemPropertiesConfigurationProvider);
    DefaultConfigurationPropertiesResolver externalPropertiesResolver =
        new DefaultConfigurationPropertiesResolver(of(systemPropertiesResolver),
                                                   externalPropertiesConfigurationProvider);
    if (deploymentProperties.isEmpty()) {
      this.configurationProperties = new PropertiesResolverConfigurationProperties(externalPropertiesResolver);
    } else {
      this.configurationProperties =
          new PropertiesResolverConfigurationProperties(new DefaultConfigurationPropertiesResolver(of(externalPropertiesResolver),
                                                                                                   new MapConfigurationPropertiesProvider(deploymentProperties,
                                                                                                                                          "Deployment properties")));
    }
  }

  private List<ConfigurationPropertiesProvider> getConfigurationPropertiesProvidersFromComponents(ArtifactConfig artifactConfig,
                                                                                                  DefaultConfigurationPropertiesResolver localResolver) {
    List<ConfigurationPropertiesProvider> configConfigurationPropertiesProviders = new ArrayList<>();
    artifactConfig.getConfigFiles().stream()
        .forEach(configFile -> configFile.getConfigLines().stream()
            .forEach(configLine -> {
              for (ConfigLine componentConfigLine : configLine.getChildren()) {
                if (componentConfigLine.getNamespace() != null && componentConfigLine.getNamespace().equals(CORE_PREFIX)
                    && componentConfigLine.getIdentifier().equals(ApplicationModel.CONFIGURATION_PROPERTIES_ELEMENT)) {
                  String fileLocation = componentConfigLine.getConfigAttributes().get("file").getValue();
                  ConfigurationPropertiesComponent propertiesComponent =
                      new ConfigurationPropertiesComponent(localResolver.resolveValue(fileLocation).toString(),
                                                           externalResourceProvider);
                  DefaultComponentLocation.DefaultLocationPart locationPart =
                      new DefaultComponentLocation.DefaultLocationPart(CONFIGURATION_PROPERTIES_ELEMENT,
                                                                       of(TypedComponentIdentifier.builder()
                                                                           .withType(UNKNOWN)
                                                                           .withIdentifier(CONFIGURATION_PROPERTIES)
                                                                           .build()),
                                                                       of(configFile.getFilename()),
                                                                       of(configLine.getLineNumber()));
                  propertiesComponent.setAnnotations(ImmutableMap
                      .<QName, Object>builder().put(AbstractAnnotatedObject.LOCATION_KEY,
                                                    new DefaultComponentLocation(of(CONFIGURATION_PROPERTIES_ELEMENT),
                                                                                 Collections.singletonList(locationPart)))
                      .build());
                  configConfigurationPropertiesProviders.add(propertiesComponent);
                  try {
                    propertiesComponent.initialise();
                  } catch (InitialisationException e) {
                    throw new MuleRuntimeException(e);
                  }
                }
              }
            }));
    return configConfigurationPropertiesProviders;
  }

  /**
   * Resolves the types of each component model when possible.
   */
  public void resolveComponentTypes() {
    checkState(componentBuildingDefinitionRegistry.isPresent(),
               "ApplicationModel was created without a " + ComponentBuildingDefinitionProvider.class.getName());
    executeOnEveryComponentTree(componentModel -> {
      Optional<ComponentBuildingDefinition<?>> buildingDefinition =
          componentBuildingDefinitionRegistry.get().getBuildingDefinition(componentModel.getIdentifier());
      buildingDefinition.map(definition -> {
        ObjectTypeVisitor typeDefinitionVisitor = new ObjectTypeVisitor(componentModel);
        definition.getTypeDefinition().visit(typeDefinitionVisitor);
        componentModel.setType(typeDefinitionVisitor.getType());
        return definition;
      }).orElseGet(() -> {
        String classParameter = componentModel.getParameters().get(CLASS_ATTRIBUTE);
        if (classParameter != null) {
          try {
            componentModel.setType(ClassUtils.getClass(classParameter));
          } catch (ClassNotFoundException e) {
            throw new RuntimeConfigurationException(I18nMessageFactory.createStaticMessage(String
                .format("Could not resolve class '%s' for component '%s'", classParameter,
                        componentModel.getComponentLocation())));
          }
        }
        return null;
      });
    });
  }

  /**
   * Creates the effective application model to be used to generate the runtime objects of the mule configuration.
   */
  private void createEffectiveModel() {
    processSourcesRedeliveryPolicy();
  }

  /**
   * Process from any message source the redelivery-policy to make it part of the final pipeline.
   */
  private void processSourcesRedeliveryPolicy() {
    executeOnEveryFlow(flowComponentModel -> {
      if (!flowComponentModel.getInnerComponents().isEmpty()) {
        ComponentModel possibleSourceComponent = flowComponentModel.getInnerComponents().get(0);
        possibleSourceComponent.getInnerComponents().stream()
            .filter(childComponent -> childComponent.getIdentifier().equals(REDELIVERY_POLICY_IDENTIFIER))
            .findAny()
            .ifPresent(redeliveryPolicyComponentModel -> {
              possibleSourceComponent.getInnerComponents().remove(redeliveryPolicyComponentModel);
              flowComponentModel.getInnerComponents().add(1, redeliveryPolicyComponentModel);
            });
      }
    });
  }

  private void convertArtifactDeclarationToComponentModel(Set<ExtensionModel> extensionModels,
                                                          ArtifactDeclaration artifactDeclaration) {
    if (artifactDeclaration != null && !extensionModels.isEmpty()) {
      DslElementModelFactory elementFactory = DslElementModelFactory
          .getDefault(DslResolvingContext.getDefault(extensionModels));

      ComponentModel rootComponent = new ComponentModel.Builder()
          .setIdentifier(ComponentIdentifier.builder()
              .withNamespace(CORE_PREFIX)
              .withName(CORE_PREFIX)
              .build())
          .build();

      AtomicBoolean atLeastOneComponentAdded = new AtomicBoolean(false);

      artifactDeclaration.getGlobalElements().stream()
          .map(e -> elementFactory.create((ElementDeclaration) e))
          .filter(Optional::isPresent)
          .map(e -> e.get().getConfiguration())
          .forEach(config -> config
              .ifPresent(c -> {
                atLeastOneComponentAdded.set(true);
                ComponentModel componentModel = convertComponentConfiguration(c, true);
                componentModel.setParent(rootComponent);
                rootComponent.getInnerComponents().add(componentModel);
              }));

      if (atLeastOneComponentAdded.get()) {
        this.muleComponentModels.add(rootComponent);
      }
    }
  }

  private ComponentModel convertComponentConfiguration(ComponentConfiguration componentConfiguration, boolean isRoot) {
    ComponentModel.Builder builder = new ComponentModel.Builder()
        .setIdentifier(componentConfiguration.getIdentifier());
    if (isRoot) {
      builder.markAsRootComponent();
    }
    for (Map.Entry<String, String> parameter : componentConfiguration.getParameters().entrySet()) {
      builder.addParameter(parameter.getKey(), parameter.getValue(), false);
    }
    for (ComponentConfiguration childComponentConfiguration : componentConfiguration.getNestedComponents()) {
      builder.addChildComponentModel(convertComponentConfiguration(childComponentConfiguration, false));
    }

    componentConfiguration.getValue().ifPresent(builder::setTextContent);

    ComponentModel componentModel = builder.build();
    for (ComponentModel childComponent : componentModel.getInnerComponents()) {
      childComponent.setParent(componentModel);
    }
    return componentModel;
  }


  private ConfigurationPropertiesProvider createProviderFromGlobalProperties(ArtifactConfig artifactConfig) {
    final Map<String, ConfigurationProperty> globalProperties = new HashMap<>();

    artifactConfig.getConfigFiles().stream().forEach(configFile -> {
      configFile.getConfigLines().get(0).getChildren().stream().forEach(configLine -> {
        if (GLOBAL_PROPERTY.equals(configLine.getIdentifier())) {
          String key = configLine.getConfigAttributes().get("name").getValue();
          String rawValue = configLine.getConfigAttributes().get("value").getValue();
          globalProperties.put(key,
                               new ConfigurationProperty(format("global-property - file: %s - lineNumber %s",
                                                                configFile.getFilename(), configLine.getLineNumber()),
                                                         key, rawValue));
        }
      });
    });
    return new GlobalPropertyConfigurationPropertiesProvider(globalProperties);
  }

  /**
   * @param element element which was the source of the {@code ComponentModel}.
   * @return the {@code ComponentModel} created from the element.
   */
  // TODO MULE-9638: remove once the old parsing mechanism is not needed anymore
  public ComponentModel findComponentDefinitionModel(Node element) {
    return innerFindComponentDefinitionModel(element, muleComponentModels);
  }

  public Optional<ComponentModel> findComponentDefinitionModel(ComponentIdentifier componentIdentifier) {
    if (muleComponentModels.isEmpty()) {
      return empty();
    }
    return muleComponentModels.get(0).getInnerComponents().stream().filter(ComponentModel::isRoot)
        .filter(componentModel -> componentModel.getIdentifier().equals(componentIdentifier)).findFirst();
  }

  private void convertConfigFileToComponentModel(ArtifactConfig artifactConfig) {
    List<ConfigFile> configFiles = artifactConfig.getConfigFiles();
    ComponentModelReader componentModelReader =
        new ComponentModelReader(configurationProperties.getConfigurationPropertiesResolver());
    configFiles.stream().forEach(configFile -> {
      ComponentModel componentModel =
          componentModelReader.extractComponentDefinitionModel(configFile.getConfigLines().get(0), configFile.getFilename());
      if (muleComponentModels.isEmpty()) {
        muleComponentModels.add(componentModel);
      } else {
        // Only one componentModel as Root should be set, therefore componentModel is merged
        final ComponentModel rootComponentModel = muleComponentModels.get(0);
        muleComponentModels.set(0, new ComponentModel.Builder(rootComponentModel).merge(componentModel).build());
      }
    });

  }

  private void validateModel(Optional<ComponentBuildingDefinitionRegistry> componentBuildingDefinitionRegistry)
      throws ConfigurationException {
    if (muleComponentModels.isEmpty() || !isMuleConfigurationFile()) {
      return;
    }
    // TODO MULE-9692 all this validations will be moved to an entity that does the validation and allows to aggregate all
    // validations instead of failing fast.
    validateNameIsNotRepeated();
    validateErrorMappings();
    validateExceptionStrategyWhenAttributeIsOnlyPresentInsideChoice();
    validateErrorHandlerStructure();
    validateParameterAndChildForSameAttributeAreNotDefinedTogether();
    if (componentBuildingDefinitionRegistry.isPresent()) {
      validateNamedTopLevelElementsHaveName(componentBuildingDefinitionRegistry.get());
    }
    validateSingleElementsExistence();
  }

  private void validateParameterAndChildForSameAttributeAreNotDefinedTogether() {
    executeOnEveryMuleComponentTree(componentModel -> {
      for (String parameterName : componentModel.getParameters().keySet()) {
        if (!componentModel.isParameterValueProvidedBySchema(parameterName)) {
          String mapChildName = hyphenize(pluralize(parameterName));
          String listOrPojoChildName = hyphenize(parameterName);
          Optional<ComponentModel> childOptional =
              findRelatedChildForParameter(componentModel.getInnerComponents(), mapChildName, listOrPojoChildName);
          if (childOptional.isPresent()) {
            throw new MuleRuntimeException(createStaticMessage(
                                                               format("Component %s has a child element %s which is used for the same purpose of the configuration parameter %s. "
                                                                   + "Only one must be used.", componentModel.getIdentifier(),
                                                                      childOptional.get().getIdentifier(),
                                                                      parameterName)));
          }
        }
      }
    });
  }

  private Optional<ComponentModel> findRelatedChildForParameter(List<ComponentModel> chilrenComponents, String... possibleNames) {
    Set<String> possibleNamesSet = new HashSet<>(asList(possibleNames));
    for (ComponentModel childrenComponent : chilrenComponents) {
      if (possibleNamesSet.contains(childrenComponent.getIdentifier().getName())) {
        return of(childrenComponent);
      }
    }
    return empty();
  }

  private void validateNameIsNotRepeated() {
    Map<String, ComponentModel> existingObjectsWithName = new HashMap<>();
    executeOnEveryMuleComponentTree(componentModel -> {
      String nameAttributeValue = componentModel.getNameAttribute();
      if (nameAttributeValue != null && !ignoredNameValidationComponentList.contains(componentModel.getIdentifier())) {
        if (existingObjectsWithName.containsKey(nameAttributeValue)) {
          throw new MuleRuntimeException(createStaticMessage(
                                                             "Two configuration elements have been defined with the same global name. Global name [%s] must be unique. Clashing components are %s and %s",
                                                             nameAttributeValue,
                                                             existingObjectsWithName.get(nameAttributeValue).getIdentifier(),
                                                             componentModel.getIdentifier()));
        }
        existingObjectsWithName.put(nameAttributeValue, componentModel);
      }
    });
  }

  private boolean isMuleConfigurationFile() {
    final ComponentIdentifier rootIdentifier = muleComponentModels.get(0).getIdentifier();
    return rootIdentifier.equals(MULE_IDENTIFIER)
        || rootIdentifier.equals(MULE_DOMAIN_IDENTIFIER)
        || rootIdentifier.equals(MULE_EE_DOMAIN_IDENTIFIER);
  }

  private void validateErrorMappings() {
    executeOnEveryComponentTree(componentModel -> {
      List<ComponentModel> errorMappings = componentModel.getInnerComponents().stream()
          .filter(c -> c.getIdentifier().equals(ERROR_MAPPING_IDENTIFIER)).collect(toList());
      if (!errorMappings.isEmpty()) {
        List<ComponentModel> anyMappings = errorMappings.stream().filter(this::isErrorMappingWithSourceAny).collect(toList());
        if (anyMappings.size() > 1) {
          throw new MuleRuntimeException(createStaticMessage("Only one mapping for 'ANY' or an empty source type is allowed."));
        } else if (anyMappings.size() == 1 && !isErrorMappingWithSourceAny(errorMappings.get(errorMappings.size() - 1))) {
          throw new MuleRuntimeException(createStaticMessage("Only the last error mapping can have 'ANY' or an empty source type."));
        }
        List<String> sources = errorMappings.stream().map(model -> model.getParameters().get(SOURCE_TYPE)).collect(toList());
        List<String> distinctSources = sources.stream().distinct().collect(toList());
        if (sources.size() != distinctSources.size()) {
          throw new MuleRuntimeException(createStaticMessage(format("Repeated source types are not allowed. Offending types are '%s'.",
                                                                    on("', '").join(disjunction(sources, distinctSources)))));
        }
      }
    });
  }

  private boolean isErrorMappingWithSourceAny(ComponentModel model) {
    String sourceType = model.getParameters().get(SOURCE_TYPE);
    return sourceType == null || sourceType.equals(ANY_IDENTIFIER);
  }

  private void validateErrorHandlerStructure() {
    executeOnEveryMuleComponentTree(component -> {
      if (component.getIdentifier().equals(ERROR_HANDLER_IDENTIFIER)) {
        validateExceptionStrategiesHaveWhenAttribute(component);
        validateNoMoreThanOneRollbackExceptionStrategyWithRedelivery(component);
      }
    });
  }

  private void validateNoMoreThanOneRollbackExceptionStrategyWithRedelivery(ComponentModel component) {
    if (component.getInnerComponents().stream().filter(exceptionStrategyComponent -> {
      return exceptionStrategyComponent.getParameters().get(MAX_REDELIVERY_ATTEMPTS_ROLLBACK_ES_ATTRIBUTE) != null;
    }).count() > 1) {
      throw new MuleRuntimeException(createStaticMessage(
                                                         "Only one on-error-propagate within a error-handler can handle message redelivery. Remove one of the maxRedeliveryAttempts attributes"));
    }
  }

  private void validateExceptionStrategiesHaveWhenAttribute(ComponentModel component) {
    List<ComponentModel> innerComponents = component.getInnerComponents();
    for (int i = 0; i < innerComponents.size() - 1; i++) {
      Map<String, String> parameters = innerComponents.get(i).getParameters();
      if (parameters.get(WHEN_CHOICE_ES_ATTRIBUTE) == null && parameters.get(TYPE_ES_ATTRIBUTE) == null) {
        throw new MuleRuntimeException(createStaticMessage(
                                                           "Every handler (except for the last one) within an 'error-handler' must specify a 'when' or 'type' attribute."));
      }
    }
  }

  private void validateExceptionStrategyWhenAttributeIsOnlyPresentInsideChoice() {
    executeOnEveryMuleComponentTree(component -> {
      if (component.getIdentifier().getName().endsWith(EXCEPTION_STRATEGY_REFERENCE_ELEMENT)) {
        Node componentNode = from(component).getNode();
        if (component.getParameters().get(WHEN_CHOICE_ES_ATTRIBUTE) != null
            && !componentNode.getParentNode().getLocalName().equals(ERROR_HANDLER)
            && !componentNode.getParentNode().getLocalName().equals(MULE_ROOT_ELEMENT)) {
          throw new MuleRuntimeException(
                                         createStaticMessage("Only handlers within an error-handler can have when attribute specified"));
        }
      }
    });
  }

  private void validateNamedTopLevelElementsHaveName(ComponentBuildingDefinitionRegistry componentBuildingDefinitionRegistry)
      throws ConfigurationException {
    try {
      List<ComponentModel> topLevelComponents = muleComponentModels.get(0).getInnerComponents();
      topLevelComponents.stream().forEach(topLevelComponent -> {
        final ComponentIdentifier identifier = topLevelComponent.getIdentifier();
        componentBuildingDefinitionRegistry.getBuildingDefinition(identifier).filter(ComponentBuildingDefinition::isNamed)
            .ifPresent(buildingDefinition -> {
              if (isBlank(topLevelComponent.getNameAttribute())) {
                throw new MuleRuntimeException(createStaticMessage(format("Global element %s:%s does not provide a name attribute.",
                                                                          identifier.getNamespace(), identifier.getName())));
              }
            });
      });
    } catch (Exception e) {
      throw new ConfigurationException(e);
    }
  }

  public void executeOnEveryComponentTree(final Consumer<ComponentModel> task) {
    for (ComponentModel componentModel : muleComponentModels) {
      executeOnComponentTree(componentModel, task);
    }
  }

  public void executeOnEveryMuleComponentTree(final Consumer<ComponentModel> task) {
    for (ComponentModel componentModel : muleComponentModels) {
      executeOnComponentTree(componentModel, task);
    }
  }


  public void executeOnEveryRootElement(final Consumer<ComponentModel> task) {
    for (ComponentModel muleComponentModel : muleComponentModels) {
      for (ComponentModel componentModel : muleComponentModel.getInnerComponents()) {
        task.accept(componentModel);
      }
    }
  }

  public void executeOnEveryFlow(final Consumer<ComponentModel> task) {
    executeOnEveryRootElement(componentModel -> {
      if (ApplicationModel.FLOW_IDENTIFIER.equals(componentModel.getIdentifier())) {
        task.accept(componentModel);
      }
    });
  }

  private void executeOnComponentTree(final ComponentModel component, final Consumer<ComponentModel> task)
      throws MuleRuntimeException {
    task.accept(component);
    component.getInnerComponents().forEach((innerComponent) -> {
      executeOnComponentTree(innerComponent, task);
    });
  }

  private ComponentModel innerFindComponentDefinitionModel(Node element, List<ComponentModel> componentModels) {
    for (ComponentModel componentModel : componentModels) {
      if (from(componentModel).getNode().equals(element)) {
        return componentModel;
      }
      ComponentModel childComponentModel = innerFindComponentDefinitionModel(element, componentModel.getInnerComponents());
      if (childComponentModel != null) {
        return childComponentModel;
      }
    }
    return null;
  }

  private void validateSingleElementsExistence() {
    validateSingleElementExistence(ComponentIdentifier.buildFromStringRepresentation("munit:before-suite"));
    validateSingleElementExistence(ComponentIdentifier.buildFromStringRepresentation("munit:after-suite"));
    validateSingleElementExistence(ComponentIdentifier.buildFromStringRepresentation("munit:before-test"));
    validateSingleElementExistence(ComponentIdentifier.buildFromStringRepresentation("munit:after-test"));

  }

  private void validateSingleElementExistence(ComponentIdentifier componentIdentifier) {
    Map<ComponentIdentifier, ComponentModel> existingObjectsWithName = new HashMap<>();

    executeOnEveryMuleComponentTree(componentModel -> {
      ComponentIdentifier identifier = componentModel.getIdentifier();
      if (componentIdentifier.getNamespace().equals(identifier.getNamespace())
          && componentIdentifier.getName().equals(identifier.getName())) {

        if (existingObjectsWithName.containsKey(identifier)) {
          throw new MuleRuntimeException(createStaticMessage(
                                                             "Two configuration elements %s have been defined. Element [%s] must be unique. Clashing components are %s and %s",
                                                             identifier.getNamespace() + ":" + identifier.getName(),
                                                             identifier.getNamespace() + ":" + identifier.getName(),
                                                             componentModel.getNameAttribute(),
                                                             existingObjectsWithName.get(identifier).getNameAttribute()));
        }
        existingObjectsWithName.put(identifier, componentModel);
      }

    });
  }

  /**
   * TODO MULE-9688: When the model it's made immutable we will also provide the parent component for navigation and this will not
   * be needed anymore.
   *
   * @return the root component model
   */
  public ComponentModel getRootComponentModel() {
    return muleComponentModels.get(0);
  }

  /**
   * Find a named component configuration.
   *
   * @param name the expected value for the name attribute configuration.
   * @return the component if present, if not, an empty {@link Optional}
   */
  public Optional<ComponentModel> findTopLevelNamedComponent(String name) {
    Optional<ComponentModel> requestedComponentModelOptional = empty();
    for (ComponentModel muleComponentModel : muleComponentModels) {
      requestedComponentModelOptional = muleComponentModel.getInnerComponents().stream()
          .filter(componentModel -> name.equals(componentModel.getNameAttribute()))
          .findAny();
      if (requestedComponentModelOptional.isPresent()) {
        break;
      }
    }
    return requestedComponentModelOptional;
  }

  /**
   * Find a named component configuration.
   *
   * @param name the expected value for the name attribute configuration.
   * @return the component if present, if not, an empty {@link Optional}
   */
  // TODO MULE-11355: Make the ComponentModel haven an ComponentConfiguration internally
  public Optional<ComponentConfiguration> findTopLevelNamedElement(String name) {
    Optional<ComponentConfiguration> requestedElement = empty();
    for (ComponentModel muleComponentModel : muleComponentModels) {
      requestedElement = muleComponentModel.getInnerComponents().stream()
          .filter(componentModel -> name.equals(componentModel.getNameAttribute()))
          .map(ComponentModel::getConfiguration)
          .findAny();
      if (requestedElement.isPresent()) {
        break;
      }
    }
    return requestedElement;
  }

  /**
   * We force the current instance of {@link ApplicationModel} to be highly cohesive with {@link MacroExpansionModuleModel} as
   * it's responsibility of this object to properly initialize and expand every global element/operation into the concrete set of
   * message processors
   *
   * @param extensionModels Set of {@link ExtensionModel extensionModels} that will be used to check if the element has to be
   *        expanded.
   */
  private void expandModules(Set<ExtensionModel> extensionModels) {
    new MacroExpansionModuleModel(this, extensionModels).expand();
  }

  /**
   * @return the attributes resolver for this artifact.
   */
  public ConfigurationProperties getConfigurationProperties() {
    return configurationProperties;
  }
}

