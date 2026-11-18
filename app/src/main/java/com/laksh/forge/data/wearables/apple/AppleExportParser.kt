package com.laksh.forge.data.wearables.apple

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Streams Apple Health's export.xml. Only <Record> and <Workout> start tags are looked at and
 * nothing is retained except the aggregator's per-day totals: exports reach gigabytes, and the
 * research notes a DOM/in-memory parse of ~500 MB of XML needing ~3 GB of RAM.
 */
object AppleExportParser {

    /** How often (in records) [parse] reports progress; also how often cancellation is checked. */
    const val PROGRESS_EVERY = 5_000L

    fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    /**
     * [onProgress] receives the number of records read so far; throwing from it (e.g. a
     * cancellation) aborts the parse.
     */
    fun parse(parser: XmlPullParser, aggregator: AppleDayAggregator, onProgress: (Long) -> Unit = {}) {
        var count = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Record" -> {
                        aggregator.addRecord(
                            type = parser.getAttributeValue(null, "type"),
                            sourceName = parser.getAttributeValue(null, "sourceName"),
                            device = parser.getAttributeValue(null, "device"),
                            unit = parser.getAttributeValue(null, "unit"),
                            startDate = parser.getAttributeValue(null, "startDate"),
                            endDate = parser.getAttributeValue(null, "endDate"),
                            value = parser.getAttributeValue(null, "value")
                        )
                        count++
                        if (count % PROGRESS_EVERY == 0L) onProgress(count)
                    }
                    "Workout" -> {
                        aggregator.addWorkout(
                            sourceName = parser.getAttributeValue(null, "sourceName"),
                            device = parser.getAttributeValue(null, "device"),
                            duration = parser.getAttributeValue(null, "duration"),
                            durationUnit = parser.getAttributeValue(null, "durationUnit"),
                            startDate = parser.getAttributeValue(null, "startDate"),
                            endDate = parser.getAttributeValue(null, "endDate")
                        )
                        count++
                        if (count % PROGRESS_EVERY == 0L) onProgress(count)
                    }
                }
            }
            event = parser.next()
        }
        onProgress(count)
    }
}
