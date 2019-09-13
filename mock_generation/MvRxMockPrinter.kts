#!/usr/bin/env kscript
@file:Include("ShellAccess.kt")
@file:Include("DirectoryUtils.kt")
@file:Include("CommandLineArgs.kt")
@file:Include("MiscUtils.kt")

import picocli.CommandLine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Command line interface to copy mock MvRx state and arguments from a connected debug build.
 */
@CommandLine.Command(
    name = "MvRx Mock Printer",
    description = [
        "Generate MvRx mocks. " +
            "Creates a mock file for each active ViewModel and MvRxView in a connected debug app. " +
            "The device must have debugging enabled. " +
            "Mocks are both copied to clipboard and written to temp files. " +
            "This is intended to be run from the root directory of your project. "
    ]
)
class MvrxPrinterApi : CommandLineArgs() {

    @CommandLine.Option(
        names = ["--fragmentName", "--viewName"],
        description = [
            "Pass the name of a specific MvRx view whose states should be copied." +
                " If not specified then all MvRxViews in the Started state will be copied."
        ]
    )
    var fragmentName: String? = null

    @CommandLine.Option(
        names = ["--noArgs"],
        description = ["Pass to disable copying Fragment Arguments (if any exist)."]
    )
    var excludeFragmentArgs: Boolean = false

    @CommandLine.Option(
        names = ["--stateName"],
        description = [
            "Pass the name of a specific MvRx State to copy. Only states with a matching name will be used." +
                " If not specified then all states will be copied."
        ]
    )
    var stateName: String? = null

    @CommandLine.Option(
        names = ["--copyToModule"],
        description = [
            "Pass the module name of the expected MvRxView so that the generated mock files are copied to the same module automatically." +
                " The mocks will be copied to the same package as the MvRxView, but nested under a 'mocks' sub package."
        ]
    )
    var copyToModule: String? = null

    @CommandLine.Option(
        names = ["--listTruncationThreshold"],
        description = [
            "Any lists in the state will be truncated to this many items." +
                " This helps to reduce bloat and overhead from lists with many redundant items." +
                " Pass 0 to not truncate lists."
        ],
        defaultValue = "3",
        showDefaultValue = CommandLine.Help.Visibility.ALWAYS
    )
    var listTruncationThreshold: Int = 3

    @CommandLine.Option(
        names = ["--stringTruncationThreshold"],
        description = [
            "Any Strings in the state will be truncated to this many characters." +
                " This helps to reduce bloat and overhead from very long text." +
                " Pass 0 to not truncate Strings."
        ],
        defaultValue = "300",
        showDefaultValue = CommandLine.Help.Visibility.ALWAYS
    )
    var stringTruncationThreshold: Int = 300
}

val printerApi = parseArgs<MvrxPrinterApi>()
println("Generating mocks...")

// Logcat is cleared because we use it to get file names, and we don't want to pick up files
// from a previous output.
// This will timeout and throw an exception if no devices are connected.
"adb logcat -c".execute(
    timeoutAmount = 10,
    timeoutUnit = TimeUnit.SECONDS,
    timeoutMessage = "Make sure a device is connected to adb"
)

// This is the main command to tell the Debug build to create mock files
buildString {
    // A broadcast receiver is registered with MvRx Fragments in debug builds that will listen for this action
    // and then generate the mock files, save them to device, and print the file names to logcat so we know
    // which files to copy from device.
    append("adb shell am broadcast -a \"ACTION_COPY_MVRX_STATE\"")

    append(" --ei EXTRA_LIST_TRUNCATION_THRESHOLD ${printerApi.listTruncationThreshold}")
    append(" --ei EXTRA_STRING_TRUNCATION_THRESHOLD ${printerApi.stringTruncationThreshold}")

    if (printerApi.excludeFragmentArgs) {
        append(" --ez EXTRA_EXCLUDE_ARGS true")
    }

    printerApi.stateName?.let {
        append(" --es EXTRA_STATE_NAME $it")
    }

    printerApi.fragmentName?.let {
        append(" --es EXTRA_FRAGMENT_NAME $it")
    }
}.execute()

val outputtedFileNames = parseStateFileNamesFromLogcat()

if (outputtedFileNames.isNullOrEmpty()) {
    println("No mocks found matching given options: $printerApi")
    println("\nMake sure your Fragment is resumed, and check Logcat for tags containing 'MVRX_PRINTER' for details")
    exitProcess(1)
}

println("Copying mock files to temp folder...\n")
val tmpFolder = File(executionDirectory(), "mvrx_temp_mocks").apply {
    mkdir()
}

val applicationPackageName = getApplicationPackage()

/**
 * This pulls each file on device to a local folder in the repo (the folder is gitignored).
 * To avoid dealing with file write permissions on device the device files are written to private app
 * internal storage - so this runs the adb command as the process in order to access them.
 * This requires the device to have debugging access enabled for the connected computer.
 */
outputtedFileNames?.forEach { stateFile ->
    val fileName = stateFile.substringAfterLast("/")
    val outputFile = File(tmpFolder, fileName)
    val outputFilePath = outputFile.canonicalPath
    "adb shell \"run-as $applicationPackageName cat $stateFile\" > $outputFilePath".execute()

    // The mock is copied to clipboard for easy access if needed.
    // The user can access multiple copied states if they use a clipbard manager.
    copyToClipboard(outputFile.readText())

    printerApi.copyToModule?.let { moduleToCopyTo ->
        copyToModule(moduleToCopyTo, outputFile)
    }
    println(outputFile)
    println()
}

println("Mock files written to temp folder and copied to clipboard. Cmd+click to open from terminal.")
printerApi.copyToModule?.let { module ->
    println("  - Files also copied to module $module")
}

if (printerApi.stringTruncationThreshold > 0) {
    println("  - String truncation is enabled, Strings were truncated to ${printerApi.stringTruncationThreshold} characters.")
}

if (printerApi.listTruncationThreshold > 0) {
    println("  - List truncation is enabled, Lists were truncated to ${printerApi.listTruncationThreshold} items.")
}

println("  - Run script with -h flag to see help and options.")

getLogcatOutputForTag("MVRX_PRINTER_ERROR")
    .takeIf { it.isNotEmpty() }
    ?.let { errorOutput ->
        println("\n\nThese errors were also reported:\n")
        errorOutput.forEach { println(it) }
    }

// /////////////////////////////////////////////////
// /////////////////////////////////////////////////
// /////// Done! Helper functions below ////////////
// /////////////////////////////////////////////////
// /////////////////////////////////////////////////

/**
 * We expect the device to generate the state code and write each one to its own file on the device.
 * It will then output each filename to Logcat using a specific tag.
 * When all states are written it will output "done".
 *
 * This can take some time for large states, especially the first time as reflection caches are warmed, so a longish
 * timeout is used.
 */
fun parseStateFileNamesFromLogcat(): List<String>? = pollWithTimeout(timeoutMs = 20_000) {
    getLogcatOutputForTag("MVRX_PRINTER_RESULTS")
        .takeIf { lines -> lines.any { it.equals("done", ignoreCase = true) } }
        ?.filter {
            it.endsWith(".kt")
        }
}

fun getLogcatOutputForTag(tag: String): List<String> {
    // Open logcat
    // -d close after getting text
    // -s look for printer tag
    // -v strip the metadata and only give the original message text
    return "adb logcat -d -s $tag -v raw"
        .executeForLines()
        .filterNot { it.contains("---- beginning of") }
        .map { it.trim() }
}

inline fun <T : Any> pollWithTimeout(
    timeoutMs: Long = 10_000,
    pollFrequencyMs: Long = 500,
    block: () -> T?
): T? {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        Thread.sleep(pollFrequencyMs)
        block()?.let { return it }
    }

    println("Command timed out after $timeoutMs ms")
    return null
}

/**
 * Given a module's name, this copies the temp mock file to that module,
 * under the same package as the mocked object.
 */
fun copyToModule(moduleToCopyTo: String, tempMockFile: File) {
    val packagePath = tempMockFile.extractPackagePath() ?: run {
        println("Unable to extract package from file $tempMockFile")
        return
    }

    val moduleSourcePath = "$moduleToCopyTo/src/main/java"
    File(executionDirectory(), "$moduleSourcePath/$packagePath/${tempMockFile.name}")
        .let {
            tempMockFile.copyTo(it, overwrite = true)
        }
}

/**
 * Returns the path of the package of this file, within its source directory.
 * This expects the package directive to be the first line of the file.
 */
fun File.extractPackagePath(): String? {
    return readLines()
        .firstOrNull()
        ?.substringAfter("package")
        ?.trim()
        ?.replace(".", "/")
}

fun getApplicationPackage(): String {
    val packageIndicator = "package="
    val applicationPackage = getLogcatOutputForTag("MVRX_PRINTER_RESULTS")
        .lastOrNull { it.startsWith(packageIndicator) }
        ?.substringAfter(packageIndicator)

    if (applicationPackage.isNullOrBlank()) {
        println("Could not read application package name.")
        exitProcess(1)
    }

    return applicationPackage
}