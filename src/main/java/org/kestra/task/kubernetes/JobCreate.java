package org.kestra.task.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.Job;
import io.fabric8.kubernetes.api.model.batch.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.JobSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.kestra.core.models.annotations.Example;
import org.kestra.core.models.annotations.Plugin;
import org.kestra.core.models.annotations.PluginProperty;
import org.kestra.core.models.tasks.RunnableTask;
import org.kestra.core.runners.RunContext;
import org.kestra.task.kubernetes.models.Metadata;
import org.kestra.task.kubernetes.services.InstanceService;
import org.kestra.task.kubernetes.services.JobService;
import org.kestra.task.kubernetes.services.LoggingOutputStream;
import org.kestra.task.kubernetes.services.PodService;
import org.kestra.task.kubernetes.watchers.JobWatcher;
import org.kestra.task.kubernetes.watchers.PodWatcher;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.Map;
import javax.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a job on a kubernetes cluster."
)
@Plugin(
    examples = {
        @Example(
            code = {
                "namespace: default",
                "metadata:",
                "  labels:",
                "    my-label: my-value",
                "spec:",
                "  template:",
                "    spec:",
                "      containers:",
                "      - name: unittest",
                "        image: debian:stable-slim",
                "        command: ",
                "          - 'bash' ",
                "          - '-c'",
                "          - 'for i in {1..10}; do echo $i; sleep 0.1; done'",
                "      restartPolicy: Never"
            }
        )
    }
)
@Slf4j
public class JobCreate extends AbstractConnection implements RunnableTask<JobCreate.Output> {
    @Schema(
        title = "The namespace where the job will be created"
    )
    @PluginProperty(dynamic = true)
    @NotNull
    private String namespace;

    @Schema(
        title = "Full metadata yaml for a job."
    )
    @PluginProperty(dynamic = true)
    private Map<String, Object> metadata;

    @Schema(
        title = "Full spec yaml for a job."
    )
    @PluginProperty(dynamic = true)
    @NotNull
    private Map<String, Object> spec;


    @Schema(
        title = "If the job will be deleted on completion"
    )
    @NotNull
    @Builder.Default
    private final Boolean delete = true;

    @Override
    public JobCreate.Output run(RunContext runContext) throws Exception {
        try (KubernetesClient client = this.client(runContext)) {
            String namespace = runContext.render(this.namespace);
            Logger logger = runContext.logger();

            // create the job
            Job job = createJob(runContext, client, namespace);

            try {
                // watch for jobs
                try (
                    Watch jobWatch = JobService.jobRef(client, namespace, job).watch(listOptions(), new JobWatcher(logger));
                    LogWatch jobLogs = JobService.jobRef(client, namespace, job).watchLog(new LoggingOutputStream(logger, Level.DEBUG, "Job Log:"));
                ) {
                    // wait until pod is created
                    JobService.waitForPodCreated(client, namespace, job, this.waitUntilRunning);
                    Pod pod = JobService.findPod(client, namespace, job);

                    // watch for pods
                    try (Watch podWatch = PodService.podRef(client, namespace, pod).watch(listOptions(), new PodWatcher(logger))) {
                        // wait for pods ready
                        pod = PodService.waitForPodReady(client, namespace, pod, this.waitUntilRunning);

                        if (pod.getStatus() != null && pod.getStatus().getPhase().equals("Failed")) {
                            throw PodService.failedMessage(pod);
                        }

                        // watch log
                        try (LogWatch podLogs = PodService.podRef(client, namespace, pod)
                            .tailingLines(1000)
                            .watchLog(new LoggingOutputStream(logger, Level.INFO, null));
                        ) {
                            // wait until completion of the jobs
                            JobService.waitForJobCompletion(client, namespace, job, this.waitRunning);

                            delete(client, logger, namespace, job);

                            jobWatch.close();
                            jobLogs.close();
                            podWatch.close();
                            podLogs.close();

                            return Output.builder()
                                .job(Metadata.fromObjectMeta(job.getMetadata()))
                                .pod(Metadata.fromObjectMeta(pod.getMetadata()))
                                .build();
                        }
                    }
                }
            } catch (Exception e) {
                delete(client, logger, namespace, job);
                throw e;
            }
        }
    }

    private Job createJob(RunContext runContext, KubernetesClient client, String namespace) throws java.io.IOException, org.kestra.core.exceptions.IllegalVariableEvaluationException {
        return client.batch()
            .jobs()
            .inNamespace(namespace)
            .create(new JobBuilder()
                .withMetadata(InstanceService.fromMap(
                    ObjectMeta.class,
                    runContext,
                    this.metadata,
                    metadata(runContext)
                ))
                .withSpec(InstanceService.fromMap(
                    JobSpec.class,
                    runContext,
                    this.spec,
                    metadata(runContext)
                ))
                .build()
            );
    }

    private void delete(KubernetesClient client, Logger logger, String namespace, Job job) {
        if (delete) {
            JobService.jobRef(client, namespace, job).delete();
            logger.info("Job '{}' is deleted ", job.getMetadata().getName());
        }
    }

    @Builder
    @Getter
    public static class Output implements org.kestra.core.models.tasks.Output {
        @Schema(
            title = "The full job metadata"
        )
        private final Metadata job;

        @Schema(
            title = "The full pod metadata"
        )
        private final Metadata pod;
    }

}
