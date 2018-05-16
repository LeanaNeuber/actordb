package de.up.hpi.informationsystems.adbms.definition

import de.up.hpi.informationsystems.adbms.Util

import scala.util.Try

abstract class RowRelation extends MutableRelation {

  private var data: Seq[Record] = Seq.empty

  /** @inheritdoc */
  override def insert(record: Record): Try[Record] = Try{
    exceptionWhenNotEqual(record.columns)
    data = data :+ record
    record
  }

  /** @inheritdoc*/
  override def delete(record: Record): Try[Record] = Try{
    exceptionWhenNotEqual(record.columns)
    if(!data.contains(record))
      throw new RecordNotFoundException(s"this relation does not contain the record: $record")
    data = data.filterNot(_ == record)
    record
  }

  /** @inheritdoc*/
  override protected def internalUpdateByWhere(
        updateData: Map[UntypedColumnDef, Any], fs: Map[UntypedColumnDef, Any => Boolean]
      ): Try[Int] = Try {
    exceptionWhenNotSubset(updateData.keys)
    var counter = 0
    data = data.map( record => {
      val allFiltersApply = fs.keys
        .map { col: UntypedColumnDef => fs(col)(record(col)) }
        .forall(_ == true)

      if(allFiltersApply){
        counter += 1
        updateData.keys.foldLeft(record)((record, updateCol) => record.updated(updateCol, updateData(updateCol)))
      }
      else
        record
    })
    counter
  }

  /** @inheritdoc */
  override def where[T](f: (ColumnDef[T], T => Boolean)): Relation = TransientRelation(data).where(f)

  /** @inheritdoc */
  override def whereAll(fs: Map[UntypedColumnDef, Any => Boolean]): Relation = TransientRelation(data).whereAll(fs)

  /** @inheritdoc */
  override def project(columnDefs: Set[UntypedColumnDef]): Relation = TransientRelation(data).project(columnDefs)

  /** @inheritdoc */
  override def records: Try[Seq[Record]] = Try(data)

  /** @inheritdoc */
  override def toString: String = s"${this.getClass.getSimpleName}:\n" + Util.prettyTable(columns, data)

  @throws[IncompatibleColumnDefinitionException]
  private def exceptionWhenNotSubset(incomingColumns: Iterable[UntypedColumnDef]): Unit =
    if (!(incomingColumns.toSet subsetOf columns)) {
      val notMatchingColumns = incomingColumns.toSet -- columns
      throw IncompatibleColumnDefinitionException(s"this relation does not contain following columns: $notMatchingColumns")
    }

  @throws[IncompatibleColumnDefinitionException]
  private def exceptionWhenNotEqual(incomingColumns: Iterable[UntypedColumnDef]): Unit =
    if(incomingColumns != columns)
      throw IncompatibleColumnDefinitionException(s"the provided column layout does not match this " +
        s"relation's schema:\n$incomingColumns (provided)\n${this.columns} (relation)")
}