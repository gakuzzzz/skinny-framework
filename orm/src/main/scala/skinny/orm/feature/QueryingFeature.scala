package skinny.orm.feature

import scalikejdbc._
import skinny.Pagination
import skinny.orm.SkinnyMapperBase
import skinny.orm.feature.includes.IncludesQueryRepository

/**
 * Querying APIs feature.
 */
trait QueryingFeature[Entity]
    extends QueryingFeatureWithId[Long, Entity] {

  override def rawValueToId(value: Any) = value.toString.toLong
  override def idToRawValue(id: Long) = id
}

trait QueryingFeatureWithId[Id, Entity]
    extends SkinnyMapperBase[Entity]
    with ConnectionPoolFeature
    with AutoSessionFeature
    with AssociationsFeature[Entity]
    with IncludesFeatureWithId[Id, Entity] {

  /**
   * Appends where conditions.
   *
   * @param conditions
   * @return query builder
   */
  def where(conditions: (Symbol, Any)*): EntitiesSelectOperationBuilder = new EntitiesSelectOperationBuilder(
    mapper = this,
    conditions = conditions.flatMap {
      case (key, value) =>
        value match {
          case None => Some(sqls.isNull(defaultAlias.field(key.name)))
          case Nil => Some(sqls" FALSE") // for scalikejdbc 2.0.0 - 2.0.6 compatibility
          case values: Seq[_] => Some(sqls.in(defaultAlias.field(key.name), values))
          case value => Some(sqls.eq(defaultAlias.field(key.name), value))
        }
    }
  )

  /**
   * Appends a raw where condition.
   *
   * @param condition
   * @return query builder
   */
  def where(condition: SQLSyntax): EntitiesSelectOperationBuilder = new EntitiesSelectOperationBuilder(
    mapper = this, conditions = Seq(condition)
  )

  /**
   * Appends pagination settings as limit/offset.
   *
   * @param pagination  pagination
   * @return query builder
   */
  def paginate(pagination: Pagination): EntitiesSelectOperationBuilder = {
    new EntitiesSelectOperationBuilder(
      mapper = this, limit = Some(pagination.limit), offset = Some(pagination.offset)
    )
  }

  /**
   * Appends limit part.
   *
   * @param n value
   * @return query builder
   */
  def limit(n: Int): EntitiesSelectOperationBuilder = new EntitiesSelectOperationBuilder(mapper = this, limit = Some(n))

  /**
   * Appends offset part.
   *
   * @param n value
   * @return query builder
   */
  def offset(n: Int): EntitiesSelectOperationBuilder = new EntitiesSelectOperationBuilder(mapper = this, offset = Some(n))

  /**
   * Select query builder.
   *
   * @param mapper mapper
   * @param conditions registered conditions
   * @param limit limit
   * @param offset offset
   */
  abstract class SelectOperationBuilder(
      mapper: QueryingFeatureWithId[Id, Entity],
      conditions: Seq[SQLSyntax] = Nil,
      orderings: Seq[SQLSyntax] = Nil,
      limit: Option[Int] = None,
      offset: Option[Int] = None,
      isCountOnly: Boolean = false
  ) {

    /**
     * Appends where conditions.
     *
     * @param additionalConditions conditions
     * @return query builder
     */
    def where(additionalConditions: (Symbol, Any)*): EntitiesSelectOperationBuilder = new EntitiesSelectOperationBuilder(
      mapper = this.mapper,
      conditions = conditions ++ additionalConditions.flatMap {
      case (key, value) =>
        value match {
          case Nil => None
          case values: Seq[_] => Some(sqls.in(defaultAlias.field(key.name), values))
          case value => Some(sqls.eq(defaultAlias.field(key.name), value))
        }
    },
      orderings = orderings,
      limit = limit,
      offset = offset
    )

    /**
     * Appends a raw where condition.
     *
     * @param condition
     * @return query builder
     */
    def where(condition: SQLSyntax): EntitiesSelectOperationBuilder = new EntitiesSelectOperationBuilder(
      mapper = this.mapper,
      conditions = conditions ++ Seq(condition),
      limit = limit,
      offset = offset
    )

  }

  /**
   * Entities finder builder.
   *
   * @param mapper mapper
   * @param conditions registered conditions
   * @param limit limit
   * @param offset offset
   */
  case class EntitiesSelectOperationBuilder(
      mapper: QueryingFeatureWithId[Id, Entity],
      conditions: Seq[SQLSyntax] = Nil,
      orderings: Seq[SQLSyntax] = Nil,
      limit: Option[Int] = None,
      offset: Option[Int] = None
  ) extends SelectOperationBuilder(mapper, conditions, orderings, limit, offset, false) {

    /**
     * Appends pagination settings as limit/offset.
     *
     * @param pagination  pagination
     * @return query builder
     */
    def paginate(pagination: Pagination): EntitiesSelectOperationBuilder = {
      this.copy(limit = Some(pagination.limit), offset = Some(pagination.offset))
    }

    /**
     * Appends limit part.
     *
     * @param n value
     * @return query builder
     */
    def limit(n: Int): EntitiesSelectOperationBuilder = this.copy(limit = Some(n))

    /**
     * Appends offset part.
     *
     * @param n value
     * @return query builder
     */
    def offset(n: Int): EntitiesSelectOperationBuilder = this.copy(offset = Some(n))

    /**
     * Appends order by condition.
     *
     * @param orderings orderings
     * @return query builder
     */
    def orderBy(orderings: SQLSyntax*): EntitiesSelectOperationBuilder = this.copy(orderings = orderings)

    /**
     * Calculates rows.
     */
    def calculate(sql: SQLSyntax)(implicit s: DBSession = autoSession): BigDecimal = {
      withSQL {
        val q: SelectSQLBuilder[Entity] = select(sql).from(as(defaultAlias))
        conditions match {
          case Nil => q.where(defaultScopeWithDefaultAlias)
          case _ => conditions.tail.foldLeft(q.where(conditions.head)) {
            case (query, condition) => query.and.append(condition)
          }.and(defaultScopeWithDefaultAlias)
        }
      }.map(_.bigDecimal(1)).single.apply().map(_.toScalaBigDecimal).getOrElse(BigDecimal(0))
    }

    /**
     * Count only.
     */
    def count(fieldName: Symbol = Symbol(primaryKeyFieldName), distinct: Boolean = false)(implicit s: DBSession = autoSession): Long = {
      calculate {
        if (distinct) sqls.count(sqls.distinct(defaultAlias.field(fieldName.name)))
        else sqls.count(defaultAlias.field(fieldName.name))
      }.toLong
    }

    /**
     * Counts distinct rows.
     */
    def distinctCount(fieldName: Symbol = Symbol(primaryKeyFieldName))(implicit s: DBSession = autoSession): Long = count(fieldName, true)

    /**
     * Calculates sum of a column.
     */
    def sum(fieldName: Symbol)(implicit s: DBSession = autoSession): BigDecimal = calculate(sqls.sum(defaultAlias.field(fieldName.name)))

    /**
     * Calculates average of a column.
     */
    def average(fieldName: Symbol, decimals: Option[Int] = None)(implicit s: DBSession = autoSession): BigDecimal = {
      calculate(decimals match {
        case Some(dcml) =>
          val decimalsValue = dcml match {
            case 1 => sqls"1"
            case 2 => sqls"2"
            case 3 => sqls"3"
            case 4 => sqls"4"
            case 5 => sqls"5"
            case 6 => sqls"6"
            case 7 => sqls"7"
            case 8 => sqls"8"
            case 9 => sqls"9"
            case _ => sqls"10"
          }
          sqls"round(${sqls.avg(defaultAlias.field(fieldName.name))}, ${decimalsValue})"
        case _ =>
          sqls.avg(defaultAlias.field(fieldName.name))
      })
    }
    def avg(fieldName: Symbol, decimals: Option[Int] = None)(implicit s: DBSession = autoSession): BigDecimal = average(fieldName, decimals)

    /**
     * Calculates minimum value of a column.
     */
    def minimum(fieldName: Symbol)(implicit s: DBSession = autoSession): BigDecimal = calculate(sqls.min(defaultAlias.field(fieldName.name)))
    def min(fieldName: Symbol)(implicit s: DBSession = autoSession): BigDecimal = minimum(fieldName)

    /**
     * Calculates minimum value of a column.
     */
    def maximum(fieldName: Symbol)(implicit s: DBSession = autoSession): BigDecimal = calculate(sqls.max(defaultAlias.field(fieldName.name)))
    def max(fieldName: Symbol)(implicit s: DBSession = autoSession): BigDecimal = maximum(fieldName)

    /**
     * Actually applies SQL to the DB.
     *
     * @param session db session
     * @return query results
     */
    def apply()(implicit session: DBSession = autoSession): List[Entity] = {
      implicit val repository = IncludesQueryRepository[Entity]()

      appendIncludedAttributes(extract(withSQL {

        def query(conditions: Seq[SQLSyntax]): SQLBuilder[Entity] = {
          conditions match {
            case Nil => selectQueryWithAssociations.where(defaultScopeWithDefaultAlias)
            case _ => conditions.tail.foldLeft(selectQueryWithAssociations.where(conditions.head)) {
              case (query, condition) => query.and.append(condition)
            }.and(defaultScopeWithDefaultAlias)
          }
        }
        def appendOrderingIfExists(query: SQLBuilder[Entity]): SQLBuilder[Entity] = {
          if (orderings.isEmpty) query
          else query.append(sqls"order by").append(sqls.csv(orderings: _*))
        }
        val pagination = {
          sqls.join(Seq(limit.map(l => sqls.limit(l)), offset.map(o => sqls.offset(o))).flatten, sqls" ")
        }

        if (hasManyAssociations.size > 0 && (limit.isDefined || offset.isDefined)) {
          // find ids for pagination
          val ids: Seq[Any] = withSQL {
            lazy val allowedForDistinctQuery: Seq[SQLSyntax] = {
              columns.map(column => SQLSyntax.createUnsafely(s"${defaultAlias.tableAliasName}.${column}", Nil))
            }
            val baseQuery = {
              val columnsToFetch: Seq[SQLSyntax] = Seq(sqls"distinct ${defaultAlias.field(primaryKeyFieldName)}") ++ {
                // in this case, intentionally orderings are empty
                if (orderings.isEmpty) Nil
                else orderingsForDistinctQuery(orderings, allowedForDistinctQuery).map(removeAscDesc)
              }
              selectQueryWithAdditionalAssociations(
                select(columnsToFetch: _*).from(as(defaultAlias)),
                belongsToAssociations ++ includedBelongsToAssociations,
                hasOneAssociations ++ includedHasOneAssociations,
                hasManyAssociations ++ includedHasManyAssociations.toSet
              )
            }
            val query = (conditions match {
              case Nil => baseQuery.where(defaultScopeWithDefaultAlias)
              case _ => conditions.tail.foldLeft(baseQuery.where(conditions.head)) {
                case (query, condition) => query.and.append(condition)
              }.and(defaultScopeWithDefaultAlias)
            })

            if (orderings.isEmpty) query.append(pagination)
            else query.orderBy(sqls.csv(orderingsForDistinctQuery(orderings, allowedForDistinctQuery): _*)).append(pagination)

          }.map(_.any(1)).list.apply()

          if (ids.isEmpty) return Nil
          else {
            appendOrderingIfExists(query(conditions :+ sqls.in(defaultAlias.field(primaryKeyFieldName), ids)))
          }
        } else {
          appendOrderingIfExists(query(conditions)).append(pagination)
        }

      }).list.apply())
    }

  }

  private[this] def removeAscDesc(s: SQLSyntax): SQLSyntax = {
    SQLSyntax.createUnsafely(
      s.value
        .replaceFirst(" desc$", "").replaceFirst(" asc$", "")
        .replaceFirst(" DESC$", "").replaceFirst(" ASC$", ""),
      s.parameters
    )
  }

  private[this] def orderingsForDistinctQuery(orderings: Seq[SQLSyntax], allowedForDistinctQuery: Seq[SQLSyntax]): Seq[SQLSyntax] = {
    orderings.filter { o => allowedForDistinctQuery.exists(_.value == removeAscDesc(o).value) }
  }

}
