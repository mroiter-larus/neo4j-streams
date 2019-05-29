package streams.service.sink.strategy

import streams.events.*
import streams.extensions.quote
import streams.utils.IngestionUtils.getLabelsAsString
import streams.utils.StreamsUtils

data class SourceIdIngestionStrategyConfig(val labelName: String = "SourceEvent", val idName: String = "sourceId")

class SourceIdIngestionStrategy(config: SourceIdIngestionStrategyConfig = SourceIdIngestionStrategyConfig()): IngestionStrategy {

    private val quotedLabelName = config.labelName.quote()
    private val quotedIdName = config.idName.quote()

    override fun mergeRelationshipEvents(events: List<StreamsTransactionEvent>): List<QueryEvents> {
        if (events.isNullOrEmpty()) {
            return emptyList()
        }
        return events
                .filter { it.payload.type == EntityType.relationship && it.meta.operation != OperationType.deleted }
                .map {
                    val payload = it.payload as RelationshipPayload
                    val changeEvt = when (it.meta.operation) {
                        OperationType.deleted -> {
                            it.payload.before as RelationshipChange
                        }
                        else -> it.payload.after as RelationshipChange
                    }
                    payload.label to mapOf("id" to payload.id,
                            "start" to payload.start.id, "end" to payload.end.id, "properties" to changeEvt.properties)
                }
                .groupBy { it.first }
                .map {
                    val query = """
                        |${StreamsUtils.UNWIND}
                        |MERGE (start:$quotedLabelName{$quotedIdName: event.start})
                        |MERGE (end:$quotedLabelName{$quotedIdName: event.end})
                        |MERGE (start)-[r:${it.key.quote()}{$quotedIdName: event.id}]->(end)
                        |SET r = event.properties
                        |SET r.$quotedIdName = event.id
                    """.trimMargin()
                    QueryEvents(query, it.value.map { it.second })
                }
    }

    override fun deleteRelationshipEvents(events: List<StreamsTransactionEvent>): List<QueryEvents> {
        if (events.isNullOrEmpty()) {
            return emptyList()
        }
        return events
                .filter { it.payload.type == EntityType.relationship && it.meta.operation == OperationType.deleted }
                .map {
                    val payload = it.payload as RelationshipPayload
                    payload.label to mapOf("id" to it.payload.id)
                }
                .groupBy { it.first }
                .map {
                    val query = "${StreamsUtils.UNWIND} MATCH ()-[r:${it.key.quote()}{$quotedIdName: event.id}]-() DELETE r"
                    QueryEvents(query, it.value.map { it.second })
                }
    }

    override fun deleteNodeEvents(events: List<StreamsTransactionEvent>): List<QueryEvents> {
        if (events.isNullOrEmpty()) {
            return emptyList()
        }
        val data = events
                .filter { it.payload.type == EntityType.node && it.meta.operation == OperationType.deleted }
                .map { mapOf("id" to it.payload.id) }
        if (data.isNullOrEmpty()) {
            return emptyList()
        }
        val query = "${StreamsUtils.UNWIND} MATCH (n:$quotedLabelName{$quotedIdName: event.id}) DETACH DELETE n"
        return listOf(QueryEvents(query, data))
    }

    override fun mergeNodeEvents(events: List<StreamsTransactionEvent>): List<QueryEvents> {
        if (events.isNullOrEmpty()) {
            return emptyList()
        }
        return events
                .filter { it.payload.type == EntityType.node && it.meta.operation != OperationType.deleted }
                .map {
                    val changeEvtAfter = it.payload.after as NodeChange
                    val labelsAfter = changeEvtAfter.labels ?: emptyList()
                    val labelsBefore = if (it.payload.before != null) {
                        val changeEvtBefore = it.payload.before as NodeChange
                        changeEvtBefore.labels ?: emptyList()
                    } else {
                        emptyList()
                    }
                    val labelsToAdd = (labelsAfter - labelsBefore)
                            .toSet()
                    val labelsToDelete = (labelsBefore - labelsAfter)
                            .toSet()
                    NodeMergeMetadata(labelsToAdd = labelsToAdd, labelsToDelete = labelsToDelete) to mapOf("id" to it.payload.id, "properties" to changeEvtAfter.properties)
                }
                .groupBy { it.first }
                .map {
                    var query = """
                        |${StreamsUtils.UNWIND}
                        |MERGE (n:$quotedLabelName{$quotedIdName: event.id})
                        |SET n = event.properties
                        |SET n.$quotedIdName = event.id
                    """.trimMargin()
                    if (it.key.labelsToDelete.isNotEmpty()) {
                        query += "\nREMOVE n:${getLabelsAsString(it.key.labelsToDelete)}"
                    }
                    if (it.key.labelsToAdd.isNotEmpty()) {
                        query += "\nSET n:${getLabelsAsString(it.key.labelsToAdd)}"
                    }
                    QueryEvents(query, it.value.map { it.second })
                }
    }

}