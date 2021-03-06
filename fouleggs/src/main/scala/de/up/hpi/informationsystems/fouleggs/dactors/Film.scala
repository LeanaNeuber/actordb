package de.up.hpi.informationsystems.fouleggs.dactors

import java.time.ZonedDateTime

import de.up.hpi.informationsystems.adbms.Dactor
import de.up.hpi.informationsystems.adbms.definition.ColumnDef.UntypedColumnDef
import de.up.hpi.informationsystems.adbms.definition.{ColumnDef, RelationDef}
import de.up.hpi.informationsystems.adbms.protocols.{DefaultMessageHandling, RequestResponseProtocol}
import de.up.hpi.informationsystems.adbms.record.ColumnCellMapping._
import de.up.hpi.informationsystems.adbms.record.Record
import de.up.hpi.informationsystems.adbms.relation.{MutableRelation, Relation, RowRelation, SingleRowRelation}

import scala.util.{Failure, Success, Try}

object Film {
  // implicit default values
  import de.up.hpi.informationsystems.adbms.definition.ColumnTypeDefaults._

  object GetFilmInfo {
    sealed trait GetFilmInfo extends RequestResponseProtocol.Message
    final case class Request() extends RequestResponseProtocol.Request[GetFilmInfo]
    final case class Success(result: Relation) extends RequestResponseProtocol.Success[GetFilmInfo]
    final case class Failure(e: Throwable) extends RequestResponseProtocol.Failure[GetFilmInfo]
  }

  object AddCast {
    sealed trait AddCast extends RequestResponseProtocol.Message
    final case class Request(personId: Int, firstName: String, lastName: String, roleName: String)
      extends RequestResponseProtocol.Request[AddCast]
    final case class Success(result: Relation) extends RequestResponseProtocol.Success[AddCast]
    final case class Failure(e: Throwable) extends RequestResponseProtocol.Failure[AddCast]
  }

  object Info extends RelationDef {
    // val filmId: ColumnDef[Int] = ColumnDef[Int]("film_id") should be same as the Dactors id and therefor superfluous
    val title: ColumnDef[String] = ColumnDef[String]("film_title", "Untitled")
    val description: ColumnDef[String] = ColumnDef[String]("film_descr")
    val release: ColumnDef[ZonedDateTime] = ColumnDef[ZonedDateTime]("film_release")

    override val name: String = "film_info"
    override val columns: Set[UntypedColumnDef] = Set(title, description, release)
  }

  object Cast extends RelationDef {
    val personId: ColumnDef[Int] = ColumnDef[Int]("person_id")
    val firstName: ColumnDef[String] = ColumnDef[String]("first_name")
    val lastName: ColumnDef[String] = ColumnDef[String]("last_name")
    val roleName: ColumnDef[String] = ColumnDef[String]("role_name")

    override val name: String = "film_cast"
    override val columns: Set[UntypedColumnDef] = Set(personId, firstName, lastName, roleName)
  }

  class FilmBase(id: Int) extends Dactor(id) {
    /**
      * Returns a map of relation definition and corresponding relational store.
      *
      * @return map of relation definition and corresponding relational store
      */
    override protected val relations: Map[RelationDef, MutableRelation] =
      Map(Film.Info -> SingleRowRelation(Film.Info)) ++
      Map(Film.Cast -> RowRelation(Film.Cast))

    override def receive: Receive = {
      case GetFilmInfo.Request() => sender() ! GetFilmInfo.Success(relations(Info).immutable)
      case AddCast.Request(personId, firstName, lastName, roleName) =>
        handleAddCast(personId, firstName, lastName, roleName) match {
          case Success(record) => sender() ! AddCast.Success(Relation(Seq(record)))
          case Failure(e) => sender() ! AddCast.Failure(e)
        }
    }

    def handleAddCast(personId: Int, firstName: String, lastName: String, roleName: String): Try[Record] =
      relations(Cast).insert(Cast.newRecord(
        Cast.personId ~> personId &
        Cast.firstName ~> firstName &
        Cast.lastName ~> lastName &
        Cast.roleName ~> roleName
      ).build())
  }
}

class Film(id: Int) extends Film.FilmBase(id) with DefaultMessageHandling

