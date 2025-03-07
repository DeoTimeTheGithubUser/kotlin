package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.driver.phases.FirOutput
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.fileBelongsToModuleForPsi
import org.jetbrains.kotlin.cli.common.fir.FirDiagnosticsCompilerResultsReporter
import org.jetbrains.kotlin.cli.common.isCommonSourceForPsi
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.prepareNativeSessions
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.diagnostics.DiagnosticReporterFactory
import org.jetbrains.kotlin.fir.BinaryModuleData
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.FirResult
import org.jetbrains.kotlin.fir.pipeline.buildResolveAndCheckFir
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.library.metadata.resolver.KotlinResolvedLibrary
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.resolve.konan.platform.NativePlatformAnalyzerServices

internal fun PhaseContext.firFrontend(input: KotlinCoreEnvironment): FirOutput {
    val configuration = input.configuration
    val messageCollector = configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
    val diagnosticsReporter = DiagnosticReporterFactory.createPendingReporter()
    val renderDiagnosticNames = configuration.getBoolean(CLIConfigurationKeys.RENDER_DIAGNOSTIC_INTERNAL_NAME)
    // FIR
    val extensionRegistrars = FirExtensionRegistrar.getInstances(input.project)
    val mainModuleName = Name.special("<${config.moduleId}>")
    val ktFiles = input.getSourceFiles()
    val syntaxErrors = ktFiles.fold(false) { errorsFound, ktFile ->
        AnalyzerWithCompilerReport.reportSyntaxErrors(ktFile, messageCollector).isHasErrors or errorsFound
    }
    val binaryModuleData = BinaryModuleData.initialize(mainModuleName, CommonPlatforms.defaultCommonPlatform, NativePlatformAnalyzerServices)
    val dependencyList = DependencyListForCliModule.build(binaryModuleData) {
        dependencies(config.resolvedLibraries.getFullList().map { it.libraryFile.absolutePath })
        friendDependencies(config.friendModuleFiles.map { it.absolutePath })
        // TODO: !!! dependencies module data?
    }
    val resolvedLibraries: List<KotlinResolvedLibrary> = config.resolvedLibraries.getFullResolvedList()

    val sessionsWithSources = prepareNativeSessions(
            ktFiles, configuration, mainModuleName.asString(), resolvedLibraries, dependencyList,
            extensionRegistrars, isCommonSourceForPsi, fileBelongsToModuleForPsi
    )

    val outputs = sessionsWithSources.map { (session, sources) ->
        buildResolveAndCheckFir(session, sources, diagnosticsReporter).also {
            if (shouldPrintFiles()) {
                it.fir.forEach { println(it.render()) }
            }
        }
    }

    return if (syntaxErrors || diagnosticsReporter.hasErrors) {
        FirDiagnosticsCompilerResultsReporter.reportToMessageCollector(diagnosticsReporter, messageCollector, renderDiagnosticNames)
        FirOutput.ShouldNotGenerateCode
    } else {
        FirOutput.Full(FirResult(outputs))
    }
}
