package edu.kit.ifv.mobitopp.actitoppNG.synthesis

import edu.kit.ifv.mobitopp.actitoppNG.ActiToppHousehold
import edu.kit.ifv.mobitopp.actitoppNG.ActitoppPerson
import edu.kit.ifv.mobitopp.actitoppNG.AllChoiceModels
import edu.kit.ifv.mobitopp.actitoppNG.PersonAttributes
import edu.kit.ifv.mobitopp.actitoppNG.PlanGenerationParameters
import edu.kit.ifv.mobitopp.actitoppNG.enums.AreaType
import edu.kit.ifv.mobitopp.actitoppNG.enums.Employment
import edu.kit.ifv.mobitopp.actitoppNG.enums.Gender
import edu.kit.ifv.mobitopp.actitoppNG.modernization.DefaultPlanGeneration
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dünner Prozessadapter zwischen SyntheSis und actiToppNG.
 *
 * Eingabe:  personId;age;employment;gender
 * Ausgabe:  personId;activityType;startMinute;durationMinute
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Aufruf: SynthesisRunner <input.csv> <output.csv>" }
    val input = Path.of(args[0]).toAbsolutePath().normalize()
    val output = Path.of(args[1]).toAbsolutePath().normalize()
    require(Files.isRegularFile(input)) { "Personen-CSV fehlt: $input" }
    output.parent?.let(Files::createDirectories)

    val parameters = PlanGenerationParameters()
    val models = AllChoiceModels.create(parameters)
    val generator = DefaultPlanGeneration(models)

    Files.newBufferedReader(input).use { reader ->
        Files.newBufferedWriter(output).use { writer ->
            writer.appendLine("personId;activityType;startMinute;durationMinute")
            reader.readLine() ?: error("Personen-CSV ist leer: $input")
            reader.lineSequence().filter(String::isNotBlank).forEachIndexed { index, line ->
                val columns = line.split(';')
                require(columns.size >= 4) { "Ungültige Personenzeile ${index + 2}: $line" }
                val personId = columns[0].toLong()
                val age = columns[1].toInt()
                if (age < 6) return@forEachIndexed

                val household = ActiToppHousehold(
                    numMinorsUpTo10 = if (age <= 10) 1 else 0,
                    numMinorsBelow18 = if (age < 18) 1 else 0,
                    areaType = AreaType.PROVINCIAL,
                    numberOfCars = if (age >= 18) 1 else 0,
                )
                val person = ActitoppPerson(
                    household = household,
                    attributes = PersonAttributes(
                        gender = gender(columns[3]),
                        employment = employment(columns[2], age),
                        age = age,
                    ),
                )
                val activities = context(parameters, models) {
                    generator.generate(person).finish()
                }
                activities.forEach { activity ->
                    val start = requireNotNull(activity.startTime) {
                        "actiTopp-Aktivität ohne Startzeit für Person $personId"
                    }.inWholeMinutes
                    val duration = requireNotNull(activity.duration) {
                        "actiTopp-Aktivität ohne Dauer für Person $personId"
                    }.inWholeMinutes
                    require(duration > 0) { "Nichtpositive Aktivitätsdauer für Person $personId" }
                    writer.appendLine("$personId;${activity.activityType.name};$start;$duration")
                }
            }
        }
    }
}

private fun gender(value: String): Gender = when (value.trim().uppercase()) {
    "MALE" -> Gender.MALE
    "FEMALE" -> Gender.FEMALE
    else -> Gender.UNKNOWN
}

private fun employment(value: String, age: Int): Employment = when (value.trim().uppercase()) {
    "FULLTIME" -> Employment.FULLTIME
    "PARTTIME" -> Employment.PARTTIME
    "EDUCATION" -> when {
        age < 11 -> Employment.STUDENT_PRIMARY
        age < 19 -> Employment.STUDENT_SECONDARY
        else -> Employment.STUDENT_TERTIARY
    }
    "STUDENT" -> Employment.STUDENT_TERTIARY
    "UNEMPLOYED" -> Employment.UNOCCUPIED
    "HOMEKEEPER" -> Employment.HOUSEKEEPER
    "RETIRED" -> Employment.RETIRED
    else -> Employment.DEFINITELY_UNKNOWN
}
