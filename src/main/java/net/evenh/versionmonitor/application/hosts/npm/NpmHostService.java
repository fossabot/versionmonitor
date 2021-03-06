package net.evenh.versionmonitor.application.hosts.npm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.evenh.versionmonitor.domain.hosts.HostRegistry;
import net.evenh.versionmonitor.domain.hosts.HostService;
import net.evenh.versionmonitor.domain.projects.Project;
import net.evenh.versionmonitor.domain.projects.ProjectService;
import net.evenh.versionmonitor.domain.releases.Release;
import net.evenh.versionmonitor.domain.releases.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service("npmHostService")
public class NpmHostService implements HostService, InitializingBean {
  private static final Logger log = LoggerFactory.getLogger(NpmHostService.class);
  private static final String npmRegistry = "http://registry.npmjs.org/";

  @Autowired
  private HostRegistry registry;

  @Autowired
  private ProjectService projectService;

  @Autowired
  private ReleaseRepository releases;

  @Autowired
  private RestTemplate http;

  @Override
  public void afterPropertiesSet() throws Exception {
    registry.register(this);
  }

  @Override
  public boolean validIdentifier(String id) {
    return !(id == null || id.isEmpty() || id.length() > 214)
        && !(id.startsWith(".") || id.startsWith("-") || id.startsWith("_"));
  }

  @Override
  public boolean isSatisfiedBy(Project project) {
    return project instanceof NpmProject;
  }

  @Override
  public String getHostIdentifier() {
    return "npm";
  }

  @Override
  public Optional<? extends Project> getProject(String identifier) {
    log.debug("Processing NPM project with identifier: {}", identifier);
    try {
      NpmProjectRepresentation npm = http
          .getForObject(npmRegistry + "/" + identifier, NpmProjectRepresentation.class);
      return Optional.of(createNpmProject(npm, identifier));
    } catch (HttpClientErrorException e) {
      log.warn("Got error while fetching NPM project: {}", identifier, e);
      return Optional.empty();
    }
  }

  @Override
  public List<Release> check(Project project) throws Exception {
    Objects.requireNonNull("Supplied NPM project cannot be null");

    if (!isSatisfiedBy(project)) {
      throw new IllegalArgumentException("Project is not a NPM project: " + project);
    }

    final String prefix = this.getClass().getSimpleName() + "[" + project.getIdentifier() + "]: ";

    List<Release> newReleases = new ArrayList<>();

    List<String> existingReleases = project.getReleases().stream()
        .map(Release::getVersion)
        .collect(Collectors.toList());

    try {
      Optional<? extends Project> remoteProject = getProject(project.getIdentifier());

      if (!remoteProject.isPresent()) {
        log.warn(prefix + "Could not read project {} from NPM.", project.getIdentifier());
        return newReleases;
      } else {
        remoteProject.ifPresent(npmProject -> {

          npmProject.getReleases().forEach(release -> {
            if (!existingReleases.contains(release.getVersion())) {
              releases.saveAndFlush(release);
              newReleases.add(release);
            }
          });

          newReleases.forEach(project::addRelease);
          projectService.persist(project);
        });
      }
    } catch (Exception e) {
      log.warn(prefix + "Got exception while finding new releases", e);
    }

    log.debug(prefix + "Found {} new releases", newReleases.size());

    return newReleases;
  }

  private NpmProject createNpmProject(NpmProjectRepresentation npm, String identifier) {
    final NpmProject project = new NpmProject();

    project.setName(npm.getName());
    project.setDescription(npm.getDescription());
    project.setIdentifier(identifier);

    final List<Release> releases = new ArrayList<>();

    // Map releases
    npm.getReleases().forEach((key, value) -> {
      value.setReleased(npm.getTime().get(key));

      releases.add(mapToRelease(value, identifier));
    });

    project.setReleases(releases);

    return project;
  }

  private Release mapToRelease(NpmReleaseRepresentation release, String identifier) {
    return Release.builder()
      .withVersion(release.getVersion())
      .withUrl("https://www.npmjs.com/package/" + identifier)
      .withCreatedAt(release.getReleased())
      .build();
  }
}
