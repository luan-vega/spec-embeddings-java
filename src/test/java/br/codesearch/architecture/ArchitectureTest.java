package br.codesearch.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Regras de dependência da arquitetura hexagonal (equivalente ao test_architecture.py da versão Python). */
@AnalyzeClasses(packages = "br.codesearch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNothingOutside = noClasses()
            .that().resideInAPackage("br.codesearch.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "br.codesearch.application..", "br.codesearch.adapter..", "br.codesearch.config..",
                    "org.springframework..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdaptersOrFramework = noClasses()
            .that().resideInAPackage("br.codesearch.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "br.codesearch.adapter..", "br.codesearch.config..", "org.springframework..");

    @ArchTest
    static final ArchRule drivingAdaptersDoNotKnowDrivenAdaptersOrConfig = noClasses()
            .that().resideInAPackage("br.codesearch.adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage("br.codesearch.adapter.out..", "br.codesearch.config..");

    @ArchTest
    static final ArchRule drivenAdaptersDoNotKnowDrivingAdaptersOrConfig = noClasses()
            .that().resideInAPackage("br.codesearch.adapter.out..")
            .should().dependOnClassesThat().resideInAnyPackage("br.codesearch.adapter.in..", "br.codesearch.config..");
}
