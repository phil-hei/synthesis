package de.synthesis.kit.actitopp

import edu.kit.ifv.mobitopp.actitoppNG.*
import edu.kit.ifv.mobitopp.actitoppNG.enums.*
import edu.kit.ifv.mobitopp.actitoppNG.modernization.DefaultPlanGeneration
import java.nio.file.Files
import java.nio.file.Path

// The CSV boundary is owned by SyntheSis; the KIT sources are compiled unchanged.
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: synthesis-actitopp-adapter <input.csv> <output.csv>" }
    val input = Path.of(args[0]); val output = Path.of(args[1])
    val rows = Files.readAllLines(input).drop(1).filter(String::isNotBlank).map { it.split(';') }
    require(rows.all { it.size == 10 }) { "Expected household input contract v2 (10 columns)" }
    output.parent?.let(Files::createDirectories)
    val parameters = PlanGenerationParameters()
    val models = AllChoiceModels.create(parameters)
    val generator = DefaultPlanGeneration(models)
    require(rows.map { it[0] }.toSet().size == rows.size) { "Duplicate person IDs" }
    val contexts = mutableListOf("householdId;householdSize;childrenUpTo10;childrenBelow18;numberOfCars;areaType")
    var planned = 0
    Files.newBufferedWriter(output).use { writer ->
        writer.appendLine("personId;activityType;startMinute;durationMinute")
        rows.groupBy { it[1] }.forEach { (_, members) ->
            val first = members.first()
            require(members.all { it.subList(5, 10) == first.subList(5, 10) }) { "Conflicting household attributes" }
            require(first[5].toInt() == members.size && first[6].toInt() == members.count { it[2].toInt() <= 10 } && first[7].toInt() == members.count { it[2].toInt() < 18 }) { "Incomplete household context" }
            require(first[8].toInt() >= 0) { "Negative car count" }
            val household = ActiToppHousehold(first[6].toInt(), first[7].toInt(), AreaType.valueOf(first[9]), first[8].toInt())
            // Construct every member before planning; infants also remain in the household context.
            val persons = members.map { row ->
                val age = row[2].toInt()
                row[0] to ActitoppPerson(household, PersonAttributes(
                    gender = when(row[4]) { "MALE" -> Gender.MALE; "FEMALE" -> Gender.FEMALE; else -> Gender.UNKNOWN },
                    employment = employment(row[3], age), age = age,
                ))
            }
            contexts.add("${first[1]};${household.members.size};${household.amountOfYoungMinors()};${household.amountOfAllMinors()};${household.numberOfCars};${household.areaType.name}")
            persons.filter { it.second.age >= 6 }.forEach { (id, person) ->
                val activities = context(parameters, models) { generator.generate(person).finish() }
                activities.forEach { activity ->
                    val start = requireNotNull(activity.startTime).inWholeMinutes
                    val duration = requireNotNull(activity.duration).inWholeMinutes
                    require(start >= 0 && duration > 0) { "Invalid activity for $id" }
                    writer.appendLine("$id;${activity.activityType.name};$start;$duration")
                }
                planned++
            }
        }
    }
    Files.write(output.resolveSibling("actitopp-households.csv"), contexts)
    println("actiTopp: $planned persons planned in ${rows.groupBy { it[1] }.size} households; ${rows.size - planned} infants skipped")
}

private fun employment(value: String, age: Int): Employment = when(value) {
    "FULLTIME" -> Employment.FULLTIME
    "PARTTIME" -> Employment.PARTTIME
    "EDUCATION" -> if(age < 11) Employment.STUDENT_PRIMARY else if(age < 19) Employment.STUDENT_SECONDARY else Employment.STUDENT_TERTIARY
    "STUDENT" -> Employment.STUDENT_TERTIARY
    "UNEMPLOYED" -> Employment.UNOCCUPIED
    "HOMEKEEPER" -> Employment.HOUSEKEEPER
    "RETIRED" -> Employment.RETIRED
    else -> Employment.DEFINITELY_UNKNOWN
}
