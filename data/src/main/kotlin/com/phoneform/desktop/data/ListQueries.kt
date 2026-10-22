package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Only scalar columns cross JDBC here. Images, observations and answer revisions stay in SQLite. */
internal object ListQueries {
    private fun reviewSql(scope: String, exceptions: Boolean = true): String {
        val exception = if (!exceptions) "0" else """CASE WHEN c.id IS NULL AND (
            json_array_length(r.payload,'$.missingTemplatePageIds')>0 OR json_extract(p.payload,'$.status')='FAILED'
            OR json_extract(n.payload,'$.status')='FAILED' OR EXISTS (
                SELECT json_extract(observation.value,'$.targetKey') FROM json_each(n.payload,'$.observations') observation
                WHERE coalesce(json_extract(observation.value,'$.requiresReview'),0)=1
                EXCEPT
                SELECT json_extract(answer.value,'$.targetKey') FROM json_each(a.payload,'$.answers') answer
                WHERE coalesce(json_extract(answer.value,'$.humanEdited'),0)=1
            )) THEN 1 ELSE 0 END"""
        return """WITH review_rows AS (
            SELECT s.id submission_id,s.project_id project_id,
                CAST(json_extract(s.payload,'$.number') AS INTEGER) number,
                coalesce(json_extract(s.payload,'$.respondentId'),'') respondent_id,
                r.id revision_id,json_extract(r.payload,'$.templateVersionId') template_version_id,
                json_array_length(r.payload,'$.missingTemplatePageIds') missing_count,
                json_extract(p.payload,'$.status') preparation_status,json_extract(n.payload,'$.status') recognition_status,
                n.id recognition_id,CASE WHEN c.id IS NULL THEN 0 ELSE 1 END confirmed,$exception has_exceptions
            FROM pc_records s
            JOIN pc_records project ON project.kind='project' AND project.id=s.project_id
            JOIN pc_records r ON r.kind='revision' AND r.id=json_extract(s.payload,'$.currentRevisionId')
                AND r.project_id=s.project_id AND json_extract(r.payload,'$.submissionId')=s.id
            JOIN pc_records template ON template.kind='template' AND template.id=json_extract(r.payload,'$.templateVersionId')
                AND template.project_id=s.project_id
            LEFT JOIN pc_records ph ON ph.kind='preparation_head' AND ph.id=r.id
            LEFT JOIN pc_records p ON p.kind='preparation' AND p.id=json_extract(ph.payload,'$.id')
                AND p.project_id=s.project_id AND json_extract(p.payload,'$.submissionRevisionId')=r.id
            LEFT JOIN pc_records nh ON nh.kind='recognition_head' AND nh.id=r.id
            LEFT JOIN pc_records n ON n.kind='recognition' AND n.id=json_extract(nh.payload,'$.id')
                AND n.project_id=s.project_id AND json_extract(n.payload,'$.submissionRevisionId')=r.id
            LEFT JOIN pc_records ah ON ah.kind='answer_head' AND ah.id=r.id
            LEFT JOIN pc_records a ON a.kind='answers' AND a.id=json_extract(ah.payload,'$.id')
                AND a.project_id=s.project_id AND json_extract(a.payload,'$.submissionRevisionId')=r.id
            LEFT JOIN pc_records ch ON ch.kind='confirmation_answer' AND ch.id=a.id
            LEFT JOIN pc_records c ON c.kind='confirmation' AND c.id=json_extract(ch.payload,'$.id')
                AND c.project_id=s.project_id AND json_extract(c.payload,'$.answerRevisionId')=a.id
                AND json_extract(c.payload,'$.submissionRevisionId')=r.id
            WHERE s.kind='submission' AND coalesce(json_extract(s.payload,'$.discarded'),0)=0
                AND json_extract(s.payload,'$.currentRevisionId') IS NOT NULL AND $scope
                AND NOT EXISTS (SELECT 1 FROM pc_records d WHERE d.kind='deletion' AND d.id=s.project_id)
                AND pc_record_ok(p.kind,json_extract(p.payload,'$.recordVersion'),json_extract(p.payload,'$.status'))=1
                AND pc_record_ok(n.kind,json_extract(n.payload,'$.recordVersion'),json_extract(n.payload,'$.status'))=1
                AND pc_record_ok(a.kind,json_extract(a.payload,'$.recordVersion'),NULL)=1
                AND pc_array_ok(json_type(r.payload,'$.missingTemplatePageIds'))=1
        ) """
    }
    private const val order = "number,submission_id"
    private fun matched(query: ReviewPageQuery): String = """(pc_contains(CAST(number AS TEXT),?)=1 OR pc_contains(respondent_id,?)=1) AND ${when (query.filter) {
        ReviewListFilter.ALL -> "1=1"
        ReviewListFilter.PENDING -> "confirmed=0"
        ReviewListFilter.CONFIRMED -> "confirmed=1"
        ReviewListFilter.EXCEPTIONS -> "(has_exceptions=1 OR missing_count>0)"
    }}"""
    private fun PreparedStatement.reviewParameters(projectId: String, query: ReviewPageQuery) {
        setString(1, projectId); setString(2, query.search); setString(3, query.search)
    }
    private fun ResultSet.summary() = ReviewListSummary(getString("submission_id"),getInt("number"),getString("revision_id"),
        getInt("missing_count"),getString("preparation_status")?.let(PreparationJobStatus::valueOf),
        getString("recognition_status")?.let(RecognitionJobStatus::valueOf),getInt("confirmed")==1,getInt("has_exceptions")==1,getString("respondent_id"))

    fun reviewPage(records: Records, projectId: String, raw: ReviewPageQuery): ReviewPage {
        val query = raw.normalized()
        val base = reviewSql("s.project_id=?")
        val counts = records.connection.prepareStatement(base + """SELECT count(*) total,
            coalesce(sum(CASE WHEN confirmed=0 THEN 1 ELSE 0 END),0) pending,
            coalesce(sum(confirmed),0) confirmed,
            coalesce(sum(CASE WHEN has_exceptions=1 OR missing_count>0 THEN 1 ELSE 0 END),0) exceptions,
            coalesce(sum(CASE WHEN ${matched(query)} THEN 1 ELSE 0 END),0) matched FROM review_rows""").use { statement ->
            statement.reviewParameters(projectId,query)
            statement.executeQuery().use { row -> row.next(); IntArray(5) { row.getInt(it+1) } }
        }
        val effective = query.copy(pageIndex=query.pageIndex.coerceAtMost(if(counts[4]==0) 0 else (counts[4]-1)/query.pageSize))
        val items = records.connection.prepareStatement(base + "SELECT * FROM review_rows WHERE ${matched(effective)} ORDER BY $order LIMIT ? OFFSET ?").use { statement ->
            statement.reviewParameters(projectId,effective);statement.setInt(4,effective.pageSize);statement.setInt(5,effective.pageIndex*effective.pageSize)
            statement.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.summary()) } }
        }
        return ReviewPage(effective,items,counts[4],counts[0],counts[1],counts[2],counts[3])
    }
    fun reviewSummaries(records: Records, projectId: String): List<ReviewListSummary> = records.connection.prepareStatement(reviewSql("s.project_id=?")+"SELECT * FROM review_rows ORDER BY $order").use {
        it.setString(1,projectId);it.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.summary()) } }
    }
    fun reviewSummary(records: Records, submissionId: String): ReviewListSummary? = records.connection.prepareStatement(reviewSql("s.id=?")+"SELECT * FROM review_rows").use {
        it.setString(1,submissionId);it.executeQuery().use { rows -> if(rows.next()) rows.summary() else null }
    }
    fun neighbors(records: Records, projectId: String, submissionId: String, raw: ReviewPageQuery): ReviewNeighbors {
        val query=raw.normalized();val base=reviewSql("s.project_id=?")
        val count=records.connection.prepareStatement(base+"SELECT count(*) FROM review_rows WHERE ${matched(query)}").use {
            it.reviewParameters(projectId,query);it.executeQuery().use { row -> row.next();row.getInt(1) }
        }
        return records.connection.prepareStatement(base+""",matched_rows AS (
            SELECT submission_id,number FROM review_rows WHERE ${matched(query)}),
            anchor AS (SELECT submission_id,number FROM review_rows WHERE submission_id=?)
            SELECT (SELECT m.submission_id FROM matched_rows m WHERE m.number<a.number OR (m.number=a.number AND m.submission_id<a.submission_id) ORDER BY m.number DESC,m.submission_id DESC LIMIT 1) previous_id,
                (SELECT m.submission_id FROM matched_rows m WHERE m.number>a.number OR (m.number=a.number AND m.submission_id>a.submission_id) ORDER BY m.number,m.submission_id LIMIT 1) next_id,
                CASE WHEN EXISTS(SELECT 1 FROM matched_rows m WHERE m.submission_id=a.submission_id)
                    THEN 1+(SELECT count(*) FROM matched_rows m WHERE m.number<a.number OR (m.number=a.number AND m.submission_id<a.submission_id)) ELSE NULL END position
            FROM anchor a""").use {
            it.reviewParameters(projectId,query);it.setString(4,submissionId);it.executeQuery().use { row ->
                if(row.next()) ReviewNeighbors(row.getString("previous_id"),row.getString("next_id"),count,(row.getObject("position") as? Number)?.toInt()) else ReviewNeighbors(totalMatched=count)
            }
        }
    }
    fun rerunTargets(records: Records, projectId: String): List<RecognitionRerunTarget> = records.connection.prepareStatement(reviewSql("s.project_id=?",false)+"SELECT submission_id,revision_id,recognition_id,template_version_id FROM review_rows WHERE confirmed=0 ORDER BY $order").use {
        it.setString(1,projectId);it.executeQuery().use { rows -> buildList { while(rows.next()) add(RecognitionRerunTarget(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4))) } }
    }

    fun projects(records: Records): List<ProjectListSummary> {
        val base=reviewSql("1=1",false)
        return records.connection.prepareStatement(base+""",counts AS (
            SELECT project_id,count(*) submitted_count,
                sum(CASE WHEN missing_count>0 THEN 1 ELSE 0 END) missing_count,
                sum(CASE WHEN preparation_status IN('QUEUED','RUNNING','PAUSED') OR recognition_status IN('QUEUED','RUNNING','PAUSED') THEN 1 ELSE 0 END) preparing_count,
                sum(CASE WHEN recognition_status='READY' AND confirmed=0 THEN 1 ELSE 0 END) ready_count,
                sum(confirmed) confirmed_count,
                sum(CASE WHEN preparation_status='FAILED' OR recognition_status='FAILED' THEN 1 ELSE 0 END) failed_count
            FROM review_rows GROUP BY project_id)
            SELECT json_extract(p.payload,'$.project') project_config,
                coalesce(c.submitted_count,0) submitted_count,
                (SELECT count(*) FROM pc_records draft WHERE draft.kind='submission_draft' AND draft.project_id=p.id) draft_count,
                coalesce(c.missing_count,0) missing_count,coalesce(c.preparing_count,0) preparing_count,
                coalesce(c.ready_count,0) ready_count,coalesce(c.confirmed_count,0) confirmed_count,coalesce(c.failed_count,0) failed_count
            FROM pc_records p LEFT JOIN counts c ON c.project_id=p.id
            WHERE p.kind='project' AND NOT EXISTS(SELECT 1 FROM pc_records d WHERE d.kind='deletion' AND d.id=p.id)
            ORDER BY CAST(json_extract(p.payload,'$.project.updatedAtEpochMillis') AS INTEGER) DESC,p.id""").use { statement ->
            statement.executeQuery().use { rows -> buildList { while(rows.next()) {
                val project=RecordCodec.decode(rows.getString("project_config"),ProjectConfig::class.java)
                requireNotNull(project.questionnaireLanguage);requireNotNull(project.numberingStrategy);requireNotNull(project.preparationStatus)
                add(ProjectListSummary(project,rows.getInt("submitted_count"),rows.getInt("draft_count"),rows.getInt("missing_count"),rows.getInt("preparing_count"),rows.getInt("ready_count"),rows.getInt("confirmed_count"),rows.getInt("failed_count")))
            } } }
        }
    }

    fun exports(records: Records, projectId: String): List<ExportListSummary> = records.connection.prepareStatement("""
        SELECT e.id,e.project_id,json_extract(e.payload,'$.projectName') project_name,
            CAST(json_extract(e.payload,'$.outputVersion') AS INTEGER) output_version,
            coalesce(json_extract(e.payload,'$.includeUnresolved'),0) include_unresolved,
            CAST(json_extract(e.payload,'$.createdAtEpochMillis') AS INTEGER) created_at,
            coalesce(json_array_length(e.payload,'$.records'),0) record_count,
            (SELECT count(*) FROM json_each(e.payload,'$.excludedReasons')) excluded_count
        FROM pc_records e JOIN pc_records p ON p.kind='project' AND p.id=e.project_id
        WHERE e.kind='export' AND e.project_id=? AND json_extract(e.payload,'$.projectId')=e.project_id
            AND NOT EXISTS(SELECT 1 FROM pc_records d WHERE d.kind='deletion' AND d.id=e.project_id)
            AND pc_record_ok(e.kind,json_extract(e.payload,'$.recordVersion'),NULL)=1
        ORDER BY CAST(json_extract(e.payload,'$.createdAtEpochMillis') AS INTEGER) DESC,e.id
    """).use { statement ->
        statement.setString(1,projectId);statement.executeQuery().use { rows -> buildList { while(rows.next()) add(ExportListSummary(
            rows.getString("id"),rows.getString("project_id"),rows.getString("project_name"),rows.getInt("output_version"),
            rows.getInt("include_unresolved")==1,rows.getLong("created_at"),rows.getInt("record_count"),rows.getInt("excluded_count"))) } }
    }

    private val taskSql="""WITH tasks AS (
        SELECT j.id,j.project_id,'PREPARATION' phase,json_extract(j.payload,'$.status') status,
            CAST(json_extract(s.payload,'$.number') AS INTEGER) submission_number,NULL page_number,
            json_extract(j.payload,'$.error') error,CAST(json_extract(j.payload,'$.createdAtEpochMillis') AS INTEGER) created_at,s.id submission_id,
            NULL collection_draft_id,NULL page_id
        FROM pc_records j
        JOIN pc_records h ON h.kind='preparation_head' AND h.id=json_extract(j.payload,'$.submissionRevisionId') AND json_extract(h.payload,'$.id')=j.id
        JOIN pc_records s ON s.kind='submission' AND s.id=json_extract(j.payload,'$.submissionId') AND s.project_id=j.project_id
            AND coalesce(json_extract(s.payload,'$.discarded'),0)=0 AND json_extract(s.payload,'$.currentRevisionId')=h.id
        JOIN pc_records r ON r.kind='revision' AND r.id=h.id AND r.project_id=j.project_id AND json_extract(r.payload,'$.submissionId')=s.id
        JOIN pc_records p ON p.kind='project' AND p.id=j.project_id
        WHERE j.kind='preparation' AND pc_record_ok(j.kind,json_extract(j.payload,'$.recordVersion'),json_extract(j.payload,'$.status'))=1
            AND json_extract(j.payload,'$.status')!='SUPERSEDED' AND NOT EXISTS(SELECT 1 FROM pc_records d WHERE d.kind='deletion' AND d.id=j.project_id)
        UNION ALL
        SELECT j.id,j.project_id,'RECOGNITION',json_extract(j.payload,'$.status'),CAST(json_extract(s.payload,'$.number') AS INTEGER),NULL,
            json_extract(j.payload,'$.error'),CAST(json_extract(j.payload,'$.createdAtEpochMillis') AS INTEGER),s.id,NULL,NULL
        FROM pc_records j
        JOIN pc_records h ON h.kind='recognition_head' AND h.id=json_extract(j.payload,'$.submissionRevisionId') AND json_extract(h.payload,'$.id')=j.id
        JOIN pc_records s ON s.kind='submission' AND s.id=json_extract(j.payload,'$.submissionId') AND s.project_id=j.project_id
            AND coalesce(json_extract(s.payload,'$.discarded'),0)=0 AND json_extract(s.payload,'$.currentRevisionId')=h.id
        JOIN pc_records r ON r.kind='revision' AND r.id=h.id AND r.project_id=j.project_id AND json_extract(r.payload,'$.submissionId')=s.id
        JOIN pc_records p ON p.kind='project' AND p.id=j.project_id
        WHERE j.kind='recognition' AND pc_record_ok(j.kind,json_extract(j.payload,'$.recordVersion'),json_extract(j.payload,'$.status'))=1
            AND json_extract(j.payload,'$.status')!='SUPERSEDED' AND NOT EXISTS(SELECT 1 FROM pc_records d WHERE d.kind='deletion' AND d.id=j.project_id)
        UNION ALL
        SELECT j.id,j.project_id,'IMAGE',json_extract(j.payload,'$.status'),
            CASE WHEN json_extract(j.payload,'$.owner.kind')='SUBMISSION_REVISION' THEN CAST(json_extract(s.payload,'$.number') AS INTEGER) ELSE NULL END,
            CASE WHEN json_extract(j.payload,'$.owner.kind')='TEMPLATE_DRAFT' THEN CAST(json_extract(j.payload,'$.page.position') AS INTEGER)+1 ELSE NULL END,
            json_extract(j.payload,'$.error'),CAST(json_extract(j.payload,'$.createdAtEpochMillis') AS INTEGER),s.id,
            CASE WHEN json_extract(j.payload,'$.owner.kind')='SUBMISSION_DRAFT' THEN owner.id ELSE NULL END,
            CASE WHEN json_extract(j.payload,'$.owner.kind')='SUBMISSION_DRAFT' THEN json_extract(j.payload,'$.page.id') ELSE NULL END
        FROM pc_records j
        JOIN pc_records h ON h.kind='image_job_head' AND h.id=json_extract(j.payload,'$.owner.kind')||':'||json_extract(j.payload,'$.owner.id')||':'||json_extract(j.payload,'$.owner.revision')||':'||json_extract(j.payload,'$.page.id')||':'||json_extract(j.payload,'$.inputFingerprint') AND json_extract(h.payload,'$.id')=j.id
        JOIN pc_records p ON p.kind='project' AND p.id=j.project_id
        LEFT JOIN pc_records owner ON owner.id=json_extract(j.payload,'$.owner.id') AND owner.project_id=j.project_id AND owner.kind=CASE json_extract(j.payload,'$.owner.kind') WHEN 'TEMPLATE_DRAFT' THEN 'template_draft' WHEN 'SUBMISSION_DRAFT' THEN 'submission_draft' WHEN 'SUBMISSION_REVISION' THEN 'revision' END
        LEFT JOIN pc_records s ON s.kind='submission' AND s.id=json_extract(owner.payload,'$.submissionId') AND s.project_id=j.project_id AND coalesce(json_extract(s.payload,'$.discarded'),0)=0
        WHERE j.kind='image_job' AND pc_record_ok(j.kind,json_extract(j.payload,'$.recordVersion'),json_extract(j.payload,'$.status'))=1
            AND json_extract(j.payload,'$.status')!='SUPERSEDED' AND NOT EXISTS(SELECT 1 FROM pc_records d WHERE d.kind='deletion' AND d.id=j.project_id)
            AND CASE json_extract(j.payload,'$.owner.kind')
                WHEN 'TEMPLATE_DRAFT' THEN json_extract(owner.payload,'$.revision')=json_extract(j.payload,'$.owner.revision') AND EXISTS(SELECT 1 FROM json_each(owner.payload,'$.pages') page WHERE json_extract(page.value,'$.id')=json_extract(j.payload,'$.page.id'))
                WHEN 'SUBMISSION_DRAFT' THEN json_extract(owner.payload,'$.editRevision')=json_extract(j.payload,'$.owner.revision') AND s.id IS NOT NULL AND json_extract(s.payload,'$.currentRevisionId') IS json_extract(owner.payload,'$.baseRevisionId') AND EXISTS(SELECT 1 FROM json_each(owner.payload,'$.pages') page WHERE json_extract(page.value,'$.image.id')=json_extract(j.payload,'$.page.id'))
                WHEN 'SUBMISSION_REVISION' THEN json_extract(owner.payload,'$.number')=json_extract(j.payload,'$.owner.revision') AND json_extract(s.payload,'$.currentRevisionId')=owner.id AND EXISTS(SELECT 1 FROM json_each(owner.payload,'$.pages') page WHERE json_extract(page.value,'$.image.id')=json_extract(j.payload,'$.page.id'))
                ELSE 0 END
    ) """
    private val taskRowsSql=taskSql+""",task_rows AS (
        SELECT tasks.*,coalesce(json_extract(p.payload,'$.project.name'),'') project_name
        FROM tasks JOIN pc_records p ON p.kind='project' AND p.id=tasks.project_id
    ) """
    private const val taskOrder="CASE status WHEN 'RUNNING' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'BLOCKED' THEN 2 WHEN 'PAUSED' THEN 3 WHEN 'QUEUED' THEN 4 ELSE 5 END,created_at DESC,phase,id"
    private fun ResultSet.taskSummary() = BackgroundTaskSummary(getString("id"),getString("project_id"),
        BackgroundTaskPhase.valueOf(getString("phase")),getString("status"),
        (getObject("submission_number") as? Number)?.toInt(),(getObject("page_number") as? Number)?.toInt(),
        getString("error"),getString("status") in setOf("FAILED","PAUSED"),getLong("created_at"),getString("project_name"),getString("submission_id"),
        getString("collection_draft_id"),getString("page_id"))
    private fun taskCounts(records: Records): Map<String,Int> = records.connection.prepareStatement(taskSql+"SELECT status,count(*) FROM tasks GROUP BY status ORDER BY status").use {
            it.executeQuery().use { rows -> buildMap { while(rows.next()) put(rows.getString(1),rows.getInt(2)) } }
        }
    private fun matchedTask(query: TaskPageQuery): String = """(pc_contains(project_name,?)=1 OR pc_contains(CAST(submission_number AS TEXT),?)=1) AND ${when(query.filter) {
        TaskListFilter.PENDING -> "status IN('QUEUED','RUNNING','PAUSED','BLOCKED')"
        TaskListFilter.FAILED -> "status='FAILED'"
        TaskListFilter.COMPLETED -> "status='READY'"
        TaskListFilter.ALL -> "1=1"
    }}"""
    private fun PreparedStatement.taskParameters(query: TaskPageQuery) {
        setString(1,query.search);setString(2,query.search)
    }
    fun tasks(records: Records, limit: Int): BackgroundTaskPage {
        require(limit in 1..200) { "TASK_PAGE_INVALID" }
        val counts=taskCounts(records)
        val items=records.connection.prepareStatement(taskRowsSql+"SELECT * FROM task_rows ORDER BY $taskOrder LIMIT ?").use {
            it.setInt(1,limit);it.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.taskSummary()) } }
        }
        return BackgroundTaskPage(items,counts.values.sum(),counts)
    }
    fun taskPage(records: Records, raw: TaskPageQuery): TaskQueryPage {
        val query=raw.normalized()
        // Validate/count every current row before a search or LIMIT can hide an unsupported record.
        val counts=taskCounts(records)
        val totalMatched=records.connection.prepareStatement(taskRowsSql+"SELECT count(*) FROM task_rows WHERE ${matchedTask(query)}").use {
            it.taskParameters(query);it.executeQuery().use { row -> row.next();row.getInt(1) }
        }
        val effective=query.copy(pageIndex=query.pageIndex.coerceAtMost(if(totalMatched==0) 0 else (totalMatched-1)/query.pageSize))
        val items=records.connection.prepareStatement(taskRowsSql+"SELECT * FROM task_rows WHERE ${matchedTask(effective)} ORDER BY $taskOrder LIMIT ? OFFSET ?").use {
            it.taskParameters(effective);it.setInt(3,effective.pageSize);it.setInt(4,effective.pageIndex*effective.pageSize)
            it.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.taskSummary()) } }
        }
        return TaskQueryPage(effective,items,totalMatched,counts.values.sum(),counts)
    }
    fun taskSummary(records: Records, id: String, phase: BackgroundTaskPhase): BackgroundTaskSummary? =
        records.connection.prepareStatement(taskRowsSql+"SELECT * FROM task_rows WHERE id=? AND phase=?").use {
            it.setString(1,id);it.setString(2,phase.name);it.executeQuery().use { rows -> if(rows.next()) rows.taskSummary() else null }
        }
}
