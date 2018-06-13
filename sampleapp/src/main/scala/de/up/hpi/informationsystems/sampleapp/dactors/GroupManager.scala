package de.up.hpi.informationsystems.sampleapp.dactors

import akka.actor.Props
import de.up.hpi.informationsystems.adbms.Dactor
import de.up.hpi.informationsystems.adbms.definition._
import de.up.hpi.informationsystems.adbms.protocols.{DefaultMessageHandling, RequestResponseProtocol}

import scala.util.{Failure, Success, Try}

object GroupManager {
  // implicit default values
  import de.up.hpi.informationsystems.adbms.definition.ColumnTypeDefaults._

  def props(id: Int): Props = Props(new GroupManager(id))

  object GetFixedDiscounts {

    case class Request(ids: Seq[Int]) extends RequestResponseProtocol.Request
    // results: i_id, fixed_disc
    case class Success(result: Seq[Record]) extends RequestResponseProtocol.Success
    case class Failure(e: Throwable) extends RequestResponseProtocol.Failure

  }

  object Discounts extends RelationDef {
    val id: ColumnDef[Int] = ColumnDef("i_id")
    val fixedDisc: ColumnDef[Double] = ColumnDef("fixed_disc")

    override val columns: Set[UntypedColumnDef] = Set(id, fixedDisc)
    override val name: String = "discounts"
  }

  class GroupManagerBase(id: Int) extends Dactor(id) {

    override protected val relations: Map[RelationDef, MutableRelation] =
      Dactor.createAsRowRelations(Seq(Discounts))

    override def receive: Receive = {
      case GetFixedDiscounts.Request(ids) =>
        getFixedDiscounts(ids) match {
          case Success(result) => sender() ! GetFixedDiscounts.Success(result)
          case Failure(e) => sender() ! GetFixedDiscounts.Failure(e)
        }
    }

    def getFixedDiscounts(ids: Seq[Int]): Try[Seq[Record]] =
      relations(Discounts)
        .where(Discounts.id -> { id: Int => ids.contains(id) })
        .records

  }
}

class GroupManager(id:Int)
  extends GroupManager.GroupManagerBase(id)
    with DefaultMessageHandling

