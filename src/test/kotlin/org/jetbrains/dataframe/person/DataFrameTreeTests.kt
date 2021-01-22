package org.jetbrains.dataframe.person

import io.kotlintest.fail
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import org.jetbrains.dataframe.*
import org.jetbrains.dataframe.api.columns.DataColumn
import org.jetbrains.dataframe.api.columns.ColumnGroup
import org.jetbrains.dataframe.io.print
import org.junit.Test

class DataFrameTreeTests : BaseTest() {

    @DataFrameType
    interface NameAndCity {
        val name: String
        val city: String?
    }

    @DataFrameType
    interface GroupedPerson {
        val nameAndCity: DataRow<NameAndCity>
        val age: Int
        val weight: Int?
    }

    val df2 = df.move { name and city }.intoGroup("nameAndCity")
    val typed2 = df2.typed<GroupedPerson>()

    val DataRowBase<NameAndCity>.name @JvmName("get-name-row") get() = this["name"] as String
    val DataRowBase<NameAndCity>.city @JvmName("get-city-row") get() = this["city"] as String?
    val DataFrameBase<NameAndCity>.name @JvmName("get-name") get() = this["name"].typed<String>()
    val DataFrameBase<NameAndCity>.city @JvmName("get-city") get() = this["city"].typed<String?>()

    val DataRowBase<GroupedPerson>.age @JvmName("get-age-row") get() = this["age"] as Int
    val DataRowBase<GroupedPerson>.weight @JvmName("get-weight-row") get() = this["weight"] as Int?
    val DataRowBase<GroupedPerson>.nameAndCity get() = this["nameAndCity"] as DataRowBase<NameAndCity>
    val DataFrameBase<GroupedPerson>.age @JvmName("get-age") get() = this["age"].typed<Int>()
    val DataFrameBase<GroupedPerson>.weight @JvmName("get-weight") get() = this["weight"].typed<Int?>()
    val DataFrameBase<GroupedPerson>.nameAndCity get() = this["nameAndCity"].grouped<NameAndCity>()

    val nameAndCity by columnGroup()

    @Test
    fun `group indexing`() {

        df2[nameAndCity][city] shouldBe typed.city
        typed2.nameAndCity.city shouldBe typed.city
        df2["nameAndCity"]["city"] shouldBe typed.city
    }

    @Test
    fun `update`() {
        val expected = typed.select { city.rename("nameAndCity") and age and weight }

        df2.update { nameAndCity }.with { it[city] } shouldBe expected
        df2.update { nameAndCity }.with { this[nameAndCity][city] } shouldBe expected
        typed2.update { nameAndCity }.with { nameAndCity.city } shouldBe expected
        typed2.update { nameAndCity }.with { it.city } shouldBe expected
    }

    @Test
    fun `slice`() {

        val expected = typed[0..2].name
        val actual = typed2[0..2].nameAndCity.name
        actual shouldBe expected
    }

    @Test
    fun `filter`() {

        val expected = typed.filter { city == null }.select { weight }
        typed2.filter { nameAndCity.city == null }.select { weight } shouldBe expected
        df2.filter { it[nameAndCity][city] == null }.select { weight } shouldBe expected
    }

    @Test
    fun `select`() {
        val expected = typed.select { name and age }
        typed2.select { nameAndCity.name and age } shouldBe expected
        df2.select { it[nameAndCity][name] and age } shouldBe expected
    }

    @Test
    fun `sort`() {
        val expected = typed.sortBy { name and age }.moveTo(1) { city }
        typed2.sortBy { nameAndCity.name and age }.ungroup { nameAndCity } shouldBe expected
    }

    @Test
    fun `move`(){

        val actual = typed2.move { nameAndCity.name }.into("name")
        actual.columnNames() shouldBe listOf("nameAndCity", "name", "age", "weight")
        actual.getGroup("nameAndCity").columnNames() shouldBe listOf("city")
    }

    @Test
    fun `groupBy`() {

        val expected = typed.groupBy { name }.max { age }
        typed2.groupBy { nameAndCity.name }.max { age } shouldBe expected
    }

    @Test
    fun selectDfs(){

        val cols = typed2.select { dfs { it.hasNulls } }
        cols shouldBe typed2.select { nameAndCity.city and weight }
    }

    @Test
    fun splitRows() {
        val selected = typed2.select { nameAndCity }
        val nested = selected.mergeRows { nameAndCity.city }
        val mergedCity by columnList<String?>("city")
        val res = nested.splitRows { nameAndCity[mergedCity] }
        val expected = selected.sortBy { nameAndCity.name }
        val actual = res.sortBy { nameAndCity.name }
        actual shouldBe expected
    }

    @Test
    fun spread() {

        val modified = df.append("Alice", 55, "Moscow", 100)
        val df2 =  modified.move { name and city }.intoGroup("nameAndCity")
        val typed2 = df2.typed<GroupedPerson>()

        val expected = modified.typed<Person>().select { name and city and age }.groupBy { city }.sortBy { city.nullsLast }.map { key1, group ->
            val ages = group.groupBy { name }
            val cityName = key1.city ?: "null"
            val isList = ages.groups.asIterable().any { it.nrow() > 1 }
            ages.map { key2, group ->
                val value = if(isList) group.age.toList() else group.age.single()
                (cityName to key2.name) to value
            }.sortedBy { it.first.second }
        }.flatten()

        val cities by columnGroup()

        fun <T> DataFrame<T>.check() {
            columnNames() shouldBe listOf("name", "cities")
            this[name] shouldBe typed.name.distinct()
            val group = this[cities]
            group.ncol() shouldBe typed.city.ndistinct
            group.columns().forEach {
                if(it.name() == "Moscow") it.type shouldBe getType<List<Int>?>()
                else it.type shouldBe getType<Int?>()
            }

            val actual = group.columns().sortedBy { it.name() }.flatMap { col ->
                rows().sortedBy { it[name] }.map { row -> (col.name() to row[name]) to row[col] }.filter { it.second != null }
            }
            actual shouldBe expected
        }

        typed2.select { nameAndCity and age }.spread { nameAndCity.city }.by { age }.into("cities").check()
        df2.select(nameAndCity, age).spread { it[nameAndCity][city] }.by(age).into(cities).check()
        df2.select(GroupedPerson::nameAndCity, GroupedPerson::age).spread { it[GroupedPerson::nameAndCity][NameAndCity::city] }.by(GroupedPerson::age).into("cities").check()
        df2.select("nameAndCity", "age").spread { it["nameAndCity"]["city"] }.by("age").into("cities").check()
    }

    @Test
    fun `spread grouped column`(){
        val grouped = typed.group { age and weight}.into("info")
        val spread = grouped.spread { city }.by("info").execute()
        spread.ncol() shouldBe typed.city.ndistinct + 1

        val expected = typed.rows().groupBy { it.name to (it.city ?: "null") }.mapValues { it.value.map { it.age to it.weight } }
        val dataCols = spread.columns().drop(1)

        dataCols.forEach { (it.isGroup() || it.isTable()) shouldBe true }

        val names = spread.name
        dataCols.forEach { col ->
            val city = col.name()
            (0 until spread.nrow()).forEach { row ->
                val name = names[row]
                val value = col[row]
                val expValues = expected[name to city]
                when{
                    expValues == null -> when(value){
                            null -> {}
                            is AnyRow -> value.isEmpty() shouldBe true
                            is AnyFrame -> value.isEmpty() shouldBe true
                    }
                    expValues.size == 1 -> {
                        value shouldNotBe null
                        val single = if(value is AnyRow) value else if(value is AnyFrame) value[0] else fail("invalid value type")
                        single.size() shouldBe 2
                        single.int("age") to single.nint("weight") shouldBe expValues[0]
                    }
                    else -> {
                        val df = value as? AnyFrame
                        df shouldNotBe null
                        df!!.map { int("age") to nint("weight") }.sortedBy { it.first } shouldBe expValues.sortedBy { it.first }
                    }
                }
            }
        }
    }

    @Test
    fun splitCols() {

        val split = typed2.split { nameAndCity.name }.by { it.toCharArray().toList() }.inward().into { "char$it" }
        split.columnNames() shouldBe typed2.columnNames()
        split.nrow() shouldBe typed2.nrow()
        split.nameAndCity.columnNames() shouldBe typed2.nameAndCity.columnNames()
        val nameGroup = split.nameAndCity.name.asGroup()
        nameGroup.name() shouldBe "name"
        nameGroup.isGroup() shouldBe true
        nameGroup.ncol() shouldBe typed2.nameAndCity.name.map { it.length }.max()
        nameGroup.columnNames() shouldBe (0 until nameGroup.ncol()).map { "char$it" }
    }

    @Test
    fun `split into parts`() {

        val split = typed2.split { nameAndCity.name }.by { it.toCharArray().toList() }.intoParts()
        split.nameAndCity.columnNames() shouldBe (0 until split.nameAndCity.ncol() -1).map { "part$it" } + "city"
    }

    @Test
    fun `split into rows`() {

        val split = typed2.split { nameAndCity.name }.by { it.toCharArray().toList() }.intoRows()
        val merged = split.mergeRows { nameAndCity.name }
        val joined = merged.update { nameAndCity.name }.cast<List<Char>>().with { it.joinToString("") }
        joined shouldBe typed2
    }

    @Test
    fun `all except`(){
        val info by columnGroup()
        val moved = typed.group { except(name) }.into(info)
        val actual = moved.select { except(info) }
        actual.print()
        actual shouldBe typed.select { name }
    }

    @Test
    fun `move and group`(){
        val info by columnGroup()
        val moved = typed.group { except(name) }.into(info)
        val grouped = moved.groupBy { except(info) }.plain()
        grouped.nrow() shouldBe typed.name.ndistinct
    }

    @Test
    fun `merge rows into table`() {

        val info by columnGroup()
        val moved = typed.group { except(name) }.into(info)
        val merged = moved.mergeRows { info }
        val grouped = typed.groupBy { name }.updateGroups { remove { name } }
        val expected = grouped.plain().rename(grouped.groups).into(info)
        merged shouldBe expected
    }

    @Test
    fun `update grouped column to table`(){
        val info by columnGroup()
        val grouped = typed.group { age and weight }.into(info)
        val updated = grouped.update(info).with2 { row, column -> column.asGroup().df}
        val col = updated[info.name()]
        col.kind() shouldBe ColumnKind.Frame
        val table = col.asTable()
        table.df.columnNames() shouldBe typed.select { age and weight }.columnNames()
    }

    @Test
    fun extensionPropertiesTest() {
        val code = CodeGenerator().generateExtensionProperties(GroupedPerson::class)
        val dataFrameBase = DataFrameBase::class.simpleName
        val dataFrameRowBase = DataRowBase::class.simpleName
        val dataFrameRow = DataRow::class.qualifiedName
        val className = GroupedPerson::class.qualifiedName
        val shortName = GroupedPerson::class.simpleName!!
        val nameAndCity = NameAndCity::class.qualifiedName
        val groupedColumn = ColumnGroup::class.qualifiedName
        val columnData = DataColumn::class.qualifiedName
        val expected = """
            val $dataFrameBase<$className>.age: $columnData<kotlin.Int> @JvmName("${shortName}_age") get() = this["age"] as $columnData<kotlin.Int>
            val $dataFrameRowBase<$className>.age: Int @JvmName("${shortName}_age") get() = this["age"] as Int
            val $dataFrameBase<$className>.nameAndCity: $groupedColumn<$nameAndCity> @JvmName("${shortName}_nameAndCity") get() = this["nameAndCity"] as $groupedColumn<$nameAndCity>
            val $dataFrameRowBase<$className>.nameAndCity: $dataFrameRow<$nameAndCity> @JvmName("${shortName}_nameAndCity") get() = this["nameAndCity"] as $dataFrameRow<$nameAndCity>
            val $dataFrameBase<$className>.weight: $columnData<kotlin.Int?> @JvmName("${shortName}_weight") get() = this["weight"] as $columnData<kotlin.Int?>
            val $dataFrameRowBase<$className>.weight: Int? @JvmName("${shortName}_weight") get() = this["weight"] as Int?
        """.trimIndent()
        code shouldBe expected
    }
}