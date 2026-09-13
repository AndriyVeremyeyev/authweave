package io.authweave.core;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.authweave.core.assessment.seed.SyntheticAssessmentSeeder;

@SpringBootApplication
public class CoreApiApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--store-catalog-impact")) {
            SpringApplication application = new SpringApplication(CoreApiApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("local-catalog-impact-write");
            int exitCode = 0;
            try (var context = application.run(args)) {
                var result = context.getBean(io.authweave.core.catalog.impact.LocalCatalogImpactCommand.class).store(System.getenv());
                var report = result.report();
                System.out.printf("%s report=%s proposal=%s version=%d status=%s approval=false activation=false%n",
                        result.changed() ? "SAVED" : "UNCHANGED", report.reportId(), report.proposalId(), report.proposalVersion(), report.report().get("status").asText());
            } catch (io.authweave.core.catalog.proposal.CatalogProposalException | io.authweave.core.catalog.impact.CatalogImpactReportException failure) {
                System.err.println("Catalog impact command rejected: " + failure.getMessage()); exitCode = 2;
            } catch (IllegalArgumentException failure) {
                System.err.println("Invalid catalog impact command: " + failure.getMessage()); exitCode = 2;
            } catch (RuntimeException failure) {
                System.err.println("Catalog impact command failed (" + failure.getClass().getSimpleName() + "). No success is claimed."); exitCode = 1;
            }
            System.exit(exitCode);
            return;
        }
        if (Arrays.asList(args).contains("--store-catalog-proposal")) {
            SpringApplication application = new SpringApplication(CoreApiApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("local-catalog-write");
            int exitCode = 0;
            try (var context = application.run(args)) {
                var result = context.getBean(io.authweave.core.catalog.proposal.LocalCatalogProposalCommand.class).store(System.getenv());
                System.out.printf("%s proposal=%s version=%d state=PROPOSED approval=false activation=false%n",
                        result.changed() ? "SAVED" : "UNCHANGED", result.proposal().proposalId(), result.proposal().version());
            } catch (io.authweave.core.catalog.proposal.CatalogProposalException failure) {
                System.err.println("Catalog proposal command rejected: " + failure.reason());
                exitCode = 2;
            } catch (IllegalArgumentException failure) {
                System.err.println("Invalid catalog proposal command: " + failure.getMessage());
                exitCode = 2;
            } catch (RuntimeException failure) {
                System.err.println("Catalog proposal command failed (" + failure.getClass().getSimpleName() + "). No success is claimed.");
                exitCode = 1;
            }
            System.exit(exitCode);
            return;
        }
        if (Arrays.asList(args).contains("--seed-assessments")) {
            SpringApplication application = new SpringApplication(CoreApiApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("local-seed");
            try (var context = application.run(args)) {
                var results = context.getBean(SyntheticAssessmentSeeder.class).seed();
                System.out.println("Synthetic workspace: " + SyntheticAssessmentSeeder.WORKSPACE_ID.value());
                results.forEach(result -> System.out.printf("%s %s /api/v1/workspaces/%s/assessments/%s%n",
                        result.created() ? "CREATED" : "SKIPPED (already exists)", result.key(),
                        SyntheticAssessmentSeeder.WORKSPACE_ID.value(), result.id()));
            }
            return;
        }
        SpringApplication.run(CoreApiApplication.class, args);
    }

}
