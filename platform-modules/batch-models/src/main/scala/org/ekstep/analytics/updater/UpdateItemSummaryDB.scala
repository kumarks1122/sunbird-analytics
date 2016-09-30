package org.ekstep.analytics.updater

import org.ekstep.analytics.framework.IBatchModel
import org.ekstep.analytics.framework._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.ekstep.analytics.framework.util.CommonUtil
import java.util.Calendar
import java.text.SimpleDateFormat
import org.ekstep.analytics.framework.DataFilter
import org.ekstep.analytics.framework.Filter
import org.ekstep.analytics.framework.Period._
import com.datastax.spark.connector._
import org.ekstep.analytics.util.Constants
import org.ekstep.analytics.framework.util.JSONUtils

case class ItemUsageSummaryFact(d_period: Int, d_tag: String, d_content_id: String, d_item_id: String, m_total_ts: Double, m_total_count: Int, m_correct_res_count: Int, m_inc_res_count: Int, m_correct_res: List[String], m_top5_incorrect_res: List[String], m_avg_ts: Double) extends AlgoOutput
case class ItemUsageSummaryFact_T( itemMetrics: ItemUsageSummaryFact, syncts: Long) extends AlgoOutput
case class ItemUsageSummaryIndex(d_period: Int, d_tag: String, d_content_id: String, d_item_id: String) extends Output

object UpdateItemSummaryDB extends IBatchModelTemplate[DerivedEvent, DerivedEvent, ItemUsageSummaryFact, ItemUsageSummaryIndex] with Serializable {

    val className = "org.ekstep.analytics.updater.UpdateItemSummaryDB"
    override def name: String = "UpdateItemSummaryDB"

    override def preProcess(data: RDD[DerivedEvent], config: Map[String, AnyRef])(implicit sc: SparkContext): RDD[DerivedEvent] = {
        DataFilter.filter(data, Filter("eid", "EQ", Option("ME_ITEM_USAGE_SUMMARY")));
    }

    override def algorithm(data: RDD[DerivedEvent], config: Map[String, AnyRef])(implicit sc: SparkContext): RDD[ItemUsageSummaryFact] = {

        val itemSummary = data.map { x =>

            val period = x.dimensions.period.get;
            val tag = x.dimensions.tag.get;
            val content = x.dimensions.content_id.get;
            val item = x.dimensions.item_id.get;

            val eksMap = x.edata.eks.asInstanceOf[Map[String, AnyRef]]

            val total_ts = eksMap.get("total_ts").get.asInstanceOf[Double]
            val total_count = eksMap.get("total_count").get.asInstanceOf[Int]
            val correct_res_count = eksMap.get("correct_res_count").get.asInstanceOf[Int]
            val inc_res_count = eksMap.get("inc_res_count").get.asInstanceOf[Int]
            val top5_incorrect_res = eksMap.get("top5_incorrect_res").get.asInstanceOf[List[Map[String, String]]].map { x => JSONUtils.serialize(x) }
            val correct_res = eksMap.get("correct_res").get.asInstanceOf[List[String]]
            val avg_ts = CommonUtil.roundDouble(total_ts/total_count, 2)
            ItemUsageSummaryFact_T(ItemUsageSummaryFact(period, tag, content, item, total_ts, total_count, correct_res_count, inc_res_count, correct_res, top5_incorrect_res, avg_ts), x.context.date_range.to);
        }.cache();
        
        itemSummary.map { x => x.itemMetrics }.union(rollup(itemSummary, WEEK)).union(rollup(itemSummary, MONTH)).union(rollup(itemSummary, CUMULATIVE)).cache();
    }

    override def postProcess(data: RDD[ItemUsageSummaryFact], config: Map[String, AnyRef])(implicit sc: SparkContext): RDD[ItemUsageSummaryIndex] = {
        // Update the database
        data.saveToCassandra(Constants.CONTENT_KEY_SPACE_NAME, Constants.ITEM_USAGE_SUMMARY_FACT)
        data.map { x => ItemUsageSummaryIndex(x.d_period, x.d_tag, x.d_content_id, x.d_item_id) };
    }
    
    private def rollup(data: RDD[ItemUsageSummaryFact_T], period: Period): RDD[ItemUsageSummaryFact] = {

        val currentData = data.map { x =>
            val d_period = CommonUtil.getPeriod(x.syncts, period);
            val summ = x.itemMetrics
            (ItemUsageSummaryIndex(d_period, summ.d_tag, summ.d_content_id, summ.d_item_id), x.itemMetrics);
        }
        val prvData = currentData.map { x => x._1 }.joinWithCassandraTable[ItemUsageSummaryFact](Constants.CONTENT_KEY_SPACE_NAME, Constants.ITEM_USAGE_SUMMARY_FACT).on(SomeColumns("d_period", "d_tag","d_content_id","d_item_id"));
        val joinedData = currentData.leftOuterJoin(prvData)
        val rollupSummaries = joinedData.map { x =>
            val index = x._1
            val newSumm = x._2._1
            val prvSumm = x._2._2.getOrElse(ItemUsageSummaryFact(index.d_period, index.d_tag, index.d_content_id, index.d_item_id, 0.0, 0, 0, 0, List(), List(), 0.0))
            reduce(prvSumm, newSumm, period);
        }
        rollupSummaries;
    }

    private def reduce(fact1: ItemUsageSummaryFact, fact2: ItemUsageSummaryFact, period: Period): ItemUsageSummaryFact = {
        val total_ts = fact2.m_total_ts + fact1.m_total_ts
        val total_count = fact1.m_total_count + fact2.m_total_count
        val correct_res_count = fact1.m_correct_res_count + fact2.m_correct_res_count
        val inc_res_count = fact1.m_inc_res_count + fact2.m_inc_res_count
        val correct_res = fact1.m_correct_res
        val top5_incorrect_res = (fact1.m_top5_incorrect_res ++ fact2.m_top5_incorrect_res).groupBy(x => x)
        .map { x => (x._2.size, x._1 ) }.toArray.sorted(Ordering.by((_: (Int, String))._1).reverse).take(5).map{x=>x._2}.toList;
        val avg_ts =  CommonUtil.roundDouble((total_ts / total_count),2)
        ItemUsageSummaryFact(fact1.d_period, fact1.d_tag, fact1.d_content_id, fact1.d_item_id, total_ts, total_count, correct_res_count, inc_res_count, correct_res, top5_incorrect_res, avg_ts);
    }

}