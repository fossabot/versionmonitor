package net.evenh.versionmonitor.services.hosts;

import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import net.evenh.versionmonitor.HostRegistry;
import net.evenh.versionmonitor.models.Release;
import net.evenh.versionmonitor.models.projects.AbstractProject;
import net.evenh.versionmonitor.models.projects.GitHubProject;
import net.evenh.versionmonitor.repositories.ProjectRepository;
import net.evenh.versionmonitor.repositories.ReleaseRepository;
import net.evenh.versionmonitor.services.HostService;

import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.OkHttpConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GitHub service is responsible for communicating with GitHub, including monitoring rate limits.
 *
 * @author Even Holthe
 * @since 2016-01-09
 */
@Service("gitHubService")
public class GitHubService implements HostService, InitializingBean {
  private static final Logger logger = LoggerFactory.getLogger(GitHubService.class);

  @Autowired
  private HostRegistry registry;

  @Autowired
  private ProjectRepository repository;

  @Autowired
  private ReleaseRepository releases;

  @Value("${github.oauthToken}")
  private String authToken;

  @Value("${github.ratelimit.buffer}")
  private Integer rateLimitBuffer;

  @Value("${github.cache.size}")
  private Integer cacheSize;

  private GitHub gitHub;

  private final Pattern matcher = Pattern.compile("^[a-z0-9-_]+/[a-z0-9-_]+$",
          Pattern.CASE_INSENSITIVE);

  private GitHubService() {
  }

  /**
   * Performs the initial connection to GitHub.
   *
   * @throws IllegalArgumentException Thrown if OAuth2 Token is not configured or improperly
   *                                  configured.
   * @throws IOException              Thrown if there is problems communicating with GitHub
   *                                  unrelated to the OAuth2 token.
   */
  @Override
  public void afterPropertiesSet() throws IllegalArgumentException, IOException {
    if (authToken == null || authToken.isEmpty()) {
      throw new IllegalArgumentException("Missing GitHub OAuth2 token");
    }

    try {
      // Set up caching
      File cacheDir = new File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString());
      Cache cache = new Cache(cacheDir, cacheSize * 1024 * 1024);

      gitHub = GitHubBuilder
              .fromEnvironment()
              .withOAuthToken(authToken)
              .withConnector(new OkHttpConnector(
                      new OkUrlFactory(new OkHttpClient().setCache(cache))
              ))
              .build();

    } catch (IOException e) {
      logger.warn("Caught exception while connecting to GitHub", e);
      throw e;
    }

    registry.register(this);

    logger.info("GitHub service up and running");
  }


  /**
   * Constructs a <code>GHRepository</code> with populated data for a given identifier.
   *
   * @param ownerRepo A username and project in this form: <code>apple/swift</code> for describing
   *                  the repository located at https://github.com/apple/swift.
   * @return A populated <code>GHRepository</code> object with data about the requested repository.
   * Contains metadata and releases amongst other data.
   * @throws IllegalArgumentException Thrown if a malformed project identifier is provided as the
   *                                  input argument.
   * @throws FileNotFoundException    Thrown if the repository does not exist.
   */
  public Optional<GHRepository> getRepository(final String ownerRepo) throws IllegalArgumentException,
          FileNotFoundException {
    logger.debug("Repository identifier: {}", ownerRepo);

    if (ownerRepo == null || ownerRepo.isEmpty()) {
      throw new IllegalArgumentException("GitHub repository identifier is missing");
    } else if (!matcher.matcher(ownerRepo).matches()) {
      throw new IllegalArgumentException("Illegal GitHub repository identifier: " + ownerRepo);
    }

    try {
      return Optional.ofNullable(gitHub.getRepository(ownerRepo));
    } catch (FileNotFoundException e) {
      logger.info("GitHub repository does not exist: {}", ownerRepo);
      throw e;
    } catch (IOException e) {
      logger.warn("Got exception while fetching repository", e);
    }

    return Optional.empty();
  }

  /**
   * Gets information about the remaining rate limit.
   *
   * @return A optional <code>GHRateLimit</code> object.
   */
  public Optional<GHRateLimit> getRateLimit() {
    try {
      GHRateLimit rateLimit = gitHub.getRateLimit();
      return rateLimit == null ? Optional.empty() : Optional.of(rateLimit);
    } catch (IOException e) {
      logger.warn("Got exception while requesting rate limit", e);
    }

    return Optional.empty();
  }

  @Override
  public Optional<GitHubProject> getProject(String identifier) {
    try {
      final GitHubProject project = new GitHubProject();

      getRepository(identifier).ifPresent(repository -> {
        project.setIdentifier(identifier);
        project.setName(repository.getName());
        project.setDescription(repository.getDescription());
        project.setReleases(populateGitHubReleases(repository, identifier));
      });

      return Optional.of(project);
    } catch (IllegalArgumentException e) {
      logger.warn("Illegal arguments were supplied to the GitHubService", e);
    } catch (FileNotFoundException e) {
      logger.warn("Project not found: {}", identifier);
    } catch (Exception e) {
      logger.warn("Encountered problems using the GitHub service", e);
    }

    return Optional.empty();
  }

  @Override
  public boolean satisfiedBy(AbstractProject project) {
    return (project instanceof GitHubProject);
  }

  @Override
  public List<Release> check(final AbstractProject project) throws Exception {
    Objects.requireNonNull("Supplied GitHub project cannot be null");

    if (!satisfiedBy(project)) {
      throw new IllegalArgumentException("Project is not a GitHub project: " + project);
    }

    final String prefix = this.getClass().getSimpleName() + "[" + project.getIdentifier() + "]: ";

    List<Release> newReleases = new ArrayList<>();

    if (hasReachedRateLimit()) {
      logger.info(prefix + "Reached GitHub rate limit. Returning empty list of new releases.");
      return newReleases;
    }

    List<String> existingReleases = project.getReleases()
            .stream()
            .map(Release::getVersion)
            .collect(Collectors.toList());

    try {
      Optional<GHRepository> repo = getRepository(project.getIdentifier());

      if (!repo.isPresent()) {
        logger.warn(prefix + "Could not read fetch repo from database. Returning!");
        return newReleases;
      } else {
        repo.ifPresent(ghRepo -> {
          try {
            ghRepo.listTags().forEach(tag -> {
              if (!existingReleases.contains(tag.getName())) {
                Release newRelease = Release.ReleaseBuilder.builder()
                        .fromGitHub(tag, project.getIdentifier())
                        .build();

                releases.saveAndFlush(newRelease);

                newReleases.add(newRelease);
              }
            });

            if (!newReleases.isEmpty()) {
              newReleases.forEach(project::addRelease);

              repository.save((AbstractProject) project);
            }
          } catch (IOException e) {
            logger.warn(prefix + "Got exception while fetching tags", e);
          }
        });
      }

    } catch (FileNotFoundException e) {
      logger.warn(prefix + "Project does not exist. Removed or bad access rights?");
    }

    logger.debug(prefix + "Found {} new releases", newReleases.size());
    return newReleases;
  }

  @Override
  public String getHostIdentifier() {
    return "github";
  }

  private boolean hasReachedRateLimit() {
    // Check rate limit
    Optional<GHRateLimit> rl = getRateLimit();

    if (rl.isPresent()) {
      GHRateLimit rateLimit = rl.get();

      logger.debug("{}/{} calls performed", rateLimit.remaining, rateLimit.limit);
      logger.debug("Rate limit will be reset on {}", rateLimit.getResetDate());

      int callsLeft = rateLimit.limit - (rateLimit.remaining - rateLimitBuffer);

      if (callsLeft <= 0) {
        logger.info("No GitHub calls remaining. No new release checks will be "
                + "attempted before {} has occured", rateLimit.remaining, rateLimit.getResetDate());

        return true;
      }
    } else {
      logger.warn("Could not get rate limit information! Cancelling future calls");
      return true;
    }

    return false;
  }

  /**
   * Processes a repository and creates domain releases.
   *
   * @param repository A GitHub repository
   * @param identifier The identifier with GitHub.
   * @return A list of releases, which may be empty
   */
  private List<Release> populateGitHubReleases(GHRepository repository, String identifier) {
    List<Release> releases = new ArrayList<>();

    try {
      Release.ReleaseBuilder mapper = Release.ReleaseBuilder.builder();

      releases = repository.listTags().asList()
              .stream()
              .map(tag -> mapper.fromGitHub(tag, identifier).build())
              .collect(Collectors.toList());
    } catch (IOException e) {
      logger.warn("Encountered IOException while populating GitHub releases", e);
    }

    return releases;
  }
}