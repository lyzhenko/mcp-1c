package com.alexqsoft.platform.mcp.integration

import com.alexqsoft.McpServerApplication
import com.alexqsoft.presentation.mcp.PlatformContextMcpController
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционные тесты MCP-инструментов на реальной справке установленной платформы.
 *
 * Проверяют JSON-контракт и то, что из `.hbk` до ответа доезжают сигнатуры, параметры,
 * конструкторы и перечисления — раньше они терялись на маппинге.
 */
@SpringBootTest(classes = [McpServerApplication::class])
@ActiveProfiles("test")
@DisplayName("MCP-инструменты справки платформы")
class McpServerIntegrationTest {
    private val logger = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()

    @Autowired
    private lateinit var mcpController: PlatformContextMcpController

    private fun json(text: String): JsonNode {
        logger.info { text.take(600) }
        return mapper.readTree(text)
    }

    @Test
    @DisplayName("сервер поднимается и контроллер доступен")
    fun serverStarts() {
        assertTrue(::mcpController.isInitialized)
    }

    @Test
    @DisplayName("search находит метод и отдаёт владельца и вид")
    fun searchFindsGlobalMethod() {
        val node = json(mcpController.search("СтрНайти"))
        assertTrue(node["total"].asInt() > 0, "ожидались совпадения: $node")
        val first = node["results"].first()
        assertEquals("method", first["kind"].asText())
        assertTrue(first["name"].asText().contains("СтрНайти"))
    }

    @Test
    @DisplayName("search уважает limit и offset")
    fun searchPaginates() {
        val first = json(mcpController.search("Стр", limit = 2))
        val returned = first["returned"].asInt()
        assertTrue(returned in 1..2, "returned=$returned")
        assertEquals(2, first["nextOffset"].asInt(), "total=${first["total"]}, nextOffset=${first["nextOffset"]}")
        val next = json(mcpController.search("Стр", limit = 2, offset = first["nextOffset"].asInt()))
        assertTrue(next["results"].size() > 0, "вторая страница пуста")
    }

    @Test
    @DisplayName("search по типу возвращает только этот вид")
    fun searchFiltersByType() {
        val node = json(mcpController.search("Таблица", type = "type"))
        assertTrue(node["total"].asInt() > 0)
        node["results"].forEach { assertEquals("type", it["kind"].asText()) }
    }

    @Test
    @DisplayName("пустой запрос — ошибка invalid_request, а не исключение")
    fun searchRejectsEmptyQuery() {
        val node = json(mcpController.search("   "))
        assertEquals("invalid_request", node["error"]["code"].asText())
    }

    @Test
    @DisplayName("info отдаёт сигнатуру и параметры глобального метода")
    fun infoHasSignature() {
        val node = json(mcpController.getInfo("СтрНайти", "method"))
        assertTrue(node["found"].asBoolean())
        assertEquals("method", node["kind"].asText())
        val signatures = node["signatures"]
        assertTrue(signatures != null && signatures.size() > 0, "у метода нет сигнатуры: $node")
        val parameters = signatures.first()["parameters"]
        assertTrue(parameters != null && parameters.size() >= 2, "у сигнатуры нет параметров: $node")
    }

    @Test
    @DisplayName("info по типу отдаёт счётчики состава")
    fun infoTypeHasCounts() {
        val node = json(mcpController.getInfo("ТаблицаЗначений"))
        assertEquals("type", node["kind"].asText())
        assertTrue(node["counts"]["methods"].asInt() > 0)
    }

    @Test
    @DisplayName("getMember находит свойство типа")
    fun getMemberFindsProperty() {
        val node = json(mcpController.getMember("ТаблицаЗначений", "Колонки"))
        assertEquals("property", node["kind"].asText())
        assertEquals("Колонки", node["name"].asText())
    }

    @Test
    @DisplayName("getMember отдаёт синтаксис своего метода, а не соседнего")
    fun getMemberHasOwnSyntax() {
        val node = json(mcpController.getMember("Массив", "Добавить"))
        val syntax = node["signatures"].first()["syntax"].asText()
        assertTrue(syntax.contains("Добавить"), "в сигнатуре чужой метод: $syntax")
    }

    @Test
    @DisplayName("getMembers отдаёт состав типа и синтаксис методов")
    fun getMembersHasSignatures() {
        val node = json(mcpController.getMembers("ТаблицаЗначений"))
        assertTrue(node["total"].asInt() > 0)
        val results = node["results"]
        assertTrue(results.size() > 0)
        assertTrue(
            results.any { it.has("signatures") && it["signatures"].size() > 0 },
            "ни у одного метода нет сигнатуры: $node",
        )
    }

    @Test
    @DisplayName("getMembers продолжается по offset")
    fun getMembersPaginates() {
        val node = json(mcpController.getMembers("ТаблицаЗначений", limit = 2))
        assertEquals(2, node["returned"].asInt())
        if (node["nextOffset"] != null && !node["nextOffset"].isNull) {
            val next = json(mcpController.getMembers("ТаблицаЗначений", limit = 2, offset = node["nextOffset"].asInt()))
            assertTrue(next["results"].size() > 0)
        }
    }

    @Test
    @DisplayName("getConstructors отдаёт конструкторы типа")
    fun getConstructorsHasItems() {
        val node = json(mcpController.getConstructors("Структура"))
        assertTrue(node["total"].asInt() > 0, "у Структуры нет конструкторов: $node")
        assertTrue(node["results"].first()["syntax"].asText().isNotBlank())
    }

    @Test
    @DisplayName("status показывает готовность, версию и инструменты")
    fun statusShowsCatalog() {
        val node = json(mcpController.status())
        assertEquals("ready", node["state"].asText())
        assertTrue(node["catalog"]["types"].asInt() > 0)
        assertTrue(node["server"]["tools"].any { it.asText() == "getMember" })
        assertTrue(node["platform"]["version"].asText().isNotBlank())
        assertTrue(node["platform"]["path"].asText().endsWith(".hbk"))
    }

    @Test
    @DisplayName("неизвестное имя — ошибка not_found с подсказками")
    fun unknownNameReturnsHints() {
        val node = json(mcpController.getInfo("НесуществующийЭлемент12345"))
        assertEquals("not_found", node["error"]["code"].asText())
    }

    @Test
    @DisplayName("неизвестный тип — type_not_found")
    fun unknownTypeReturnsError() {
        val node = json(mcpController.getMember("НесуществующийТип12345", "Метод"))
        assertEquals("type_not_found", node["error"]["code"].asText())
    }
}
