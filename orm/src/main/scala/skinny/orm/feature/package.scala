package skinny.orm

import scalikejdbc.ParameterBinderFactory

package object feature {

  @deprecated("Use TimestampsFeatureBase instead.", since = "1.3.4")
  type BaseTimestampsFeature[Entity] = TimestampsFeatureBase[Entity]

  private[skinny] implicit val asisParameterBinderFactory: ParameterBinderFactory[Any] = ParameterBinderFactory.asisParameterBinderFactory

}
