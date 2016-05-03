package skinny

import scalikejdbc._

/**
 * Skinny provides you Skinny-ORM as the default O/R mapper, which is built with ScalikeJDBC.
 */
package object orm {

  type Alias[A] = scalikejdbc.SyntaxProvider[A]

  private[skinny] implicit val asisParameterBinderFactory: ParameterBinderFactory[Any] = ParameterBinderFactory.asisParameterBinderFactory

}
