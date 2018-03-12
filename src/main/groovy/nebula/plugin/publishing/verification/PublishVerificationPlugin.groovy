package nebula.plugin.publishing.verification

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ComponentMetadataDetails
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.ivy.tasks.PublishToIvyRepository
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.util.GradleVersion

import java.util.concurrent.ConcurrentHashMap

class PublishVerificationPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (shouldApplyPlugin()) {
            def extension = project.extensions.create('nebulaPublishVerification', PublishVerificationExtension)
            project.plugins.withType(JavaPlugin) {
                setupPlugin(project, extension)
            }
        }
    }

    private static boolean shouldApplyPlugin() {
        GradleVersion minVersion = GradleVersion.version("4.4")
        GradleVersion.current().compareTo(minVersion) >= 0
    }

    private void setupPlugin(Project project, PublishVerificationExtension extension) {
        VerifyPublicationTask verificationTask = project.tasks.create("verifyPublication", VerifyPublicationTask)
        Map<ModuleVersionIdentifier, ComponentMetadataDetails> detailsCollector = componentMetadataCollector(project, verificationTask)

        verificationTask.details = detailsCollector
        verificationTask.ignore = extension.ignore
        verificationTask.ignoreGroups = extension.ignoreGroups
        configureHooks(project, verificationTask)
    }

    private Map<ModuleVersionIdentifier, ComponentMetadataDetails> componentMetadataCollector(Project p, Task verificationTask) {
        Map<ModuleVersionIdentifier, ComponentMetadataDetails> detailsCollector = createCollector(p)
        p.dependencies {
            components {
                all { ComponentMetadataDetails details ->
                    detailsCollector.put(details.id, details)
                }
            }
        }
        detailsCollector
    }

    private Map<ModuleVersionIdentifier, ComponentMetadataDetails> createCollector(Project project) {
        //we need one collector per the whole build. Due caching in gradle metadata rules are invoked only once
        //which can cause that we will miss some metadata
        CollectorHolderExtension rootExtension = project.rootProject.extensions.findByType(CollectorHolderExtension)
        if (rootExtension == null) {
            return project.rootProject.extensions.create('collectorHolderExtension', CollectorHolderExtension).collector
        } else {
            return rootExtension.collector
        }
    }

    private void configureHooks(Project project, Task verificationTask) {
        project.tasks.withType(PublishToIvyRepository) { task ->
            task.dependsOn(verificationTask)
        }
        project.tasks.withType(PublishToMavenRepository) { task ->
            task.dependsOn(verificationTask)
        }
        project.plugins.withId('com.jfrog.artifactory') {
            def artifactoryPublishTask = project.tasks.findByName('artifactoryPublish')
            if (artifactoryPublishTask) {
                artifactoryPublishTask.dependsOn(verificationTask)
            }
            //newer version of artifactory plugin introduced this task to do actual publishing, so we have to
            //hook even for this one.
            def artifactoryDeployTask = project.tasks.findByName("artifactoryDeploy")
            if (artifactoryDeployTask) {
                artifactoryDeployTask.dependsOn(verificationTask)
            }
        }
    }

    class CollectorHolderExtension {
        Map<ModuleVersionIdentifier, ComponentMetadataDetails> collector = new ConcurrentHashMap<>()
    }
}