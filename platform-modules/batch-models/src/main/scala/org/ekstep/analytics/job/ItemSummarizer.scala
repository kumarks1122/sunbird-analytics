package org.ekstep.analytics.job

import org.ekstep.analytics.framework.JobDriver
import org.ekstep.analytics.framework.Event
import optional.Application
import org.ekstep.analytics.framework.IJob
import org.apache.spark.SparkContext
import org.ekstep.analytics.model.ItemSummary


object ItemSummarizer extends Application with IJob {
    
      implicit val className = "org.ekstep.analytics.job.ItemSummarizer"
      
      def main(config: String)(implicit sc: Option[SparkContext] = None) {
        implicit val sparkContext: SparkContext = sc.getOrElse(null);
        JobDriver.run("batch", config, ItemSummary);
    }
}