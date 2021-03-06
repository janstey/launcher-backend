package io.fabric8.launcher.core.impl.observers;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import io.fabric8.launcher.core.api.CreateProjectile;
import io.fabric8.launcher.core.api.StatusMessageEvent;
import io.fabric8.launcher.core.api.inject.Step;
import io.fabric8.launcher.core.impl.events.CreateProjectileEvent;
import io.fabric8.launcher.service.git.api.DuplicateHookException;
import io.fabric8.launcher.service.git.api.GitHook;
import io.fabric8.launcher.service.git.api.GitRepository;
import io.fabric8.launcher.service.git.spi.GitServiceSpi;
import io.fabric8.launcher.service.github.api.GitHubService;
import io.fabric8.launcher.service.github.api.GitHubServiceFactory;
import io.fabric8.launcher.service.github.api.GitHubWebhookEvent;
import io.fabric8.launcher.service.openshift.api.OpenShiftCluster;
import io.fabric8.launcher.service.openshift.api.OpenShiftClusterRegistry;
import io.fabric8.launcher.service.openshift.api.OpenShiftProject;
import io.fabric8.launcher.service.openshift.api.OpenShiftService;
import io.fabric8.launcher.service.openshift.api.OpenShiftServiceFactory;
import org.apache.commons.lang.text.StrSubstitutor;

import static io.fabric8.launcher.core.api.StatusEventType.GITHUB_CREATE;
import static io.fabric8.launcher.core.api.StatusEventType.GITHUB_PUSHED;
import static io.fabric8.launcher.core.api.StatusEventType.GITHUB_WEBHOOK;
import static java.util.Collections.singletonMap;

/**
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
@ApplicationScoped
class GitHubStepObserver {
    @Inject
    public GitHubStepObserver(GitHubServiceFactory gitHubServiceFactory, OpenShiftServiceFactory openShiftServiceFactory, OpenShiftClusterRegistry openShiftClusterRegistry, Event<StatusMessageEvent> statusEvent) {
        this.statusEvent = statusEvent;
        this.gitHubServiceFactory = gitHubServiceFactory;
        this.openShiftServiceFactory = openShiftServiceFactory;
        this.openShiftClusterRegistry = openShiftClusterRegistry;
    }

    private final GitHubServiceFactory gitHubServiceFactory;

    private final OpenShiftServiceFactory openShiftServiceFactory;

    private final OpenShiftClusterRegistry openShiftClusterRegistry;

    private final Event<StatusMessageEvent> statusEvent;

    private Logger log = Logger.getLogger(GitHubStepObserver.class.getName());

    public void createGitHubRepository(@Observes @Step(GITHUB_CREATE) CreateProjectileEvent event) {
        // Precondition checks
        if (event.getGitHubRepository() != null) {
            throw new IllegalStateException("Github repository is already set");
        }

        CreateProjectile projectile = event.getProjectile();
        String repositoryDescription = projectile.getGitHubRepositoryDescription();
        String repositoryName = projectile.getGitHubRepositoryName();
        if (repositoryName == null) {
            repositoryName = projectile.getOpenShiftProjectName();
        }

        GitHubService gitHubService = gitHubServiceFactory.create(projectile.getGitHubIdentity());
        GitRepository gitHubRepository = gitHubService.createRepository(repositoryName, repositoryDescription);
        event.setGitHubRepository(gitHubRepository);
        statusEvent.fire(new StatusMessageEvent(projectile.getId(), GITHUB_CREATE,
                                                singletonMap("location", gitHubRepository.getHomepage())));
    }

    public void pushToGitHubRepository(@Observes @Step(GITHUB_PUSHED) CreateProjectileEvent event) {
        // Precondition checks
        if (event.getGitHubRepository() == null) {
            throw new IllegalStateException("Github repository is not set");
        }

        CreateProjectile projectile = event.getProjectile();
        GitHubService gitHubService = gitHubServiceFactory.create(projectile.getGitHubIdentity());
        GitRepository gitHubRepository = event.getGitHubRepository();
        Path projectLocation = projectile.getProjectLocation();

        // Add logged user in README.adoc
        Path readmeAdocPath = projectLocation.resolve("README.adoc");
        if (Files.exists(readmeAdocPath)) {
            try {
                String content = new String(Files.readAllBytes(readmeAdocPath));
                Map<String, String> values = new HashMap<>();
                values.put("loggedUser", gitHubService.getLoggedUser().getLogin());
                String newContent = new StrSubstitutor(values).replace(content);
                Files.write(readmeAdocPath, newContent.getBytes());
            } catch (IOException e) {
                log.log(Level.SEVERE, "Error while replacing README.adoc variables", e);
            }
        }

        gitHubService.push(gitHubRepository, projectLocation);
        statusEvent.fire(new StatusMessageEvent(projectile.getId(), GITHUB_PUSHED));
    }

    /**
     * Creates a webhook on the github repo to fire a build / deploy when changes happen on the project.
     */
    public void createWebHooks(@Observes @Step(GITHUB_WEBHOOK) CreateProjectileEvent event) {
        // Precondition checks
        if (event.getGitHubRepository() == null) {
            throw new IllegalStateException("Github repository is not set");
        }

        CreateProjectile projectile = event.getProjectile();
        Optional<OpenShiftCluster> cluster = openShiftClusterRegistry.findClusterById(projectile.getOpenShiftClusterName());
        OpenShiftService openShiftService = openShiftServiceFactory.create(cluster.get(), projectile.getOpenShiftIdentity());

        OpenShiftProject openShiftProject = openShiftService.findProject(projectile.getOpenShiftProjectName())
                .orElseThrow(() -> new IllegalStateException("Openshift project '" + projectile.getOpenShiftProjectName() + "' was not found"));
        GitHubService gitHubService = gitHubServiceFactory.create(projectile.getGitHubIdentity());
        GitRepository gitHubRepository = event.getGitHubRepository();

        List<GitHook> webhooks = new ArrayList<>();
        for (URL webhookUrl : openShiftService.getWebhookUrls(openShiftProject)) {
            GitHook gitHubWebhook;
            try {
                gitHubWebhook = gitHubService.createHook(gitHubRepository, webhookUrl,
                                                         GitHubWebhookEvent.PUSH.name(),
                                                         GitHubWebhookEvent.PULL_REQUEST.name(),
                                                         GitHubWebhookEvent.ISSUE_COMMENT.name());
            } catch (final DuplicateHookException dpe) {
                // Swallow, it's OK, we've already forked this repo
                log.log(Level.FINE, dpe.getMessage(), dpe);
                gitHubWebhook = ((GitServiceSpi) gitHubService).getWebhook(gitHubRepository, webhookUrl).orElse(null);
            }
            if (gitHubWebhook != null) {
                webhooks.add(gitHubWebhook);
            }
        }
        event.setWebhooks(webhooks);
        statusEvent.fire(new StatusMessageEvent(projectile.getId(), GITHUB_WEBHOOK));
    }
}