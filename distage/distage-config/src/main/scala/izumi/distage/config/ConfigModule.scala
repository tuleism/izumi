package izumi.distage.config

import izumi.distage.config.ConfigProvider.ConfigImport
import izumi.distage.config.model.AppConfig
import izumi.distage.model.definition.BootstrapModuleDef
import izumi.distage.model.planning.PlanningHook
import izumi.fundamentals.typesafe.config.{RuntimeConfigReader, RuntimeConfigReaderCodecs, RuntimeConfigReaderDefaultImpl}

case class ConfigInjectionOptions(
                                 enableScalars: Boolean = true
                                 , transformer: ConfigValueTransformer = ConfigValueTransformer.Null
                               )

object ConfigInjectionOptions {
  def make(
             transformer: PartialFunction[(ConfigImport, Any), Any]
           ): ConfigInjectionOptions = new ConfigInjectionOptions(transformer = new ConfigValueTransformer {
    override def transform: PartialFunction[(ConfigImport, Any), Any] = transformer
  })
}

class ConfigModule(config: AppConfig, configInjectorConfig: ConfigInjectionOptions = ConfigInjectionOptions()) extends BootstrapModuleDef {

  make[ConfigInjectionOptions].from(configInjectorConfig)

  make[AppConfig].from(config)

  many[PlanningHook]
    .add[ConfigReferenceExtractor]
    .add[ConfigProvider]

  many[RuntimeConfigReaderCodecs]
    .add(RuntimeConfigReaderCodecs.default)
  make[RuntimeConfigReader]
    .from(RuntimeConfigReaderDefaultImpl.apply _)
}