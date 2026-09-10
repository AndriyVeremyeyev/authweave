package io.authweave.core;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.authweave.core.assessment.seed.SyntheticAssessmentSeeder;

@SpringBootApplication
public class CoreApiApplication {

    public static void main(String[] args) {
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
