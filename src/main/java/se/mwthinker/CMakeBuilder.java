package se.mwthinker;

import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CMakeBuilder {
    public record ExternalProject(String name, String gitUrl, String gitTag) {}

    private boolean testProject;
    private final File projectDir;
    private final ResourceHandler resourceHandler;
    private final List<ExternalProject> externalProjects = new ArrayList<>();
    private final List<VcpkgObject> vcpkgObjects = new ArrayList<>();
    private final Set<String> vcpkgDependencies = new LinkedHashSet<>(); // Want to element keep order (to make it easier for a human to read).
    private final Set<String> linkLibraries = new LinkedHashSet<>();
    private final Set<String> sources = new LinkedHashSet<>();
    private final Set<String> extraFiles = new LinkedHashSet<>();
    private String description = "Description";

    public CMakeBuilder(File projectDir, ResourceHandler resourceHandler) {
        this.projectDir = projectDir;
        this.resourceHandler = resourceHandler;
    }

    public CMakeBuilder addExternalProjects(String name, String gitUrl, String gitTag) {
        externalProjects.add(new ExternalProject(name, gitUrl, gitTag));
        return this;
    }

    public CMakeBuilder addExternalProjectsWithDependencies(String owner, String repo) {
        Github github = new Github();
        var repositoryUrl = github.getRepositoryUrl(owner, repo);
        String commitSha = github.fetchLatestCommitSHA(owner, repo);
        var vcpkgObject = github.fetchVcpkgObject(owner, repo, commitSha);
        vcpkgObjects.add(vcpkgObject);
        return addExternalProjects(repo, repositoryUrl, commitSha);
    }

    public CMakeBuilder addVcpkgDependency(String dependency) {
        vcpkgDependencies.add(dependency);
        return this;
    }

    public CMakeBuilder addLinkLibrary(String library) {
        linkLibraries.add(library);
        return this;
    }

    public CMakeBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public CMakeBuilder addSource(String source) {
        sources.add(source);
        return this;
    }

    public CMakeBuilder addExtraFile(String file) {
        extraFiles.add(file);
        return this;
    }

    public CMakeBuilder withTestProject(boolean addTestProject) {
        this.testProject = addTestProject;
        return this;
    }

    public void buildFiles() {
        if (sources.isEmpty()) {
            throw new RuntimeException("Must at least have one source file");
        }

        saveCMakeListsTxt();

        if (!externalProjects.isEmpty()) {
            Map<String, Object> data = new HashMap<>();
            data.put("externalProjects", externalProjects);

            try (FileWriter writer = new FileWriter(new File(projectDir, "ExternalFetchContent.cmake"))) {
                resourceHandler
                        .getTemplate("ExternalFetchContent.ftl")
                        .process(data, writer);
            } catch (IOException | TemplateException e) {
                throw new RuntimeException(e);
            }
        }

        saveVcpkgJson();

        resourceHandler.copyResourceTo("CMakePresets.json", projectDir);
    }

    private void saveVcpkgJson() {
        var newVcpkgObject = new VcpkgObject();
        newVcpkgObject.setName(projectDir.getName().toLowerCase());
        newVcpkgObject.setDescription(description);
        vcpkgDependencies.forEach(newVcpkgObject::addDependency);

        Set<String> dependencies = new HashSet<>(vcpkgDependencies);
        dependencies.addAll(vcpkgObjects.stream()
                .flatMap(vcpkgObject -> vcpkgObject.getDependencies().stream())
                .toList());
        newVcpkgObject.addDependencies(dependencies.stream().toList());

        if (testProject) {
            newVcpkgObject.addDependency("gtest");
        }
        newVcpkgObject.saveToFile(new File(projectDir, "vcpkg.json"));

        if (testProject) {
            buildTestProject();
        }
    }

    private String getTestProjectName() {
        return projectDir.getName() + "_Test";
    }

    private CMakeBuilder buildTestProject() {
        File testProjectDir = Util.createFolder(projectDir, getTestProjectName());
        File sourceDir = Util.createFolder(testProjectDir, "src");
        resourceHandler.copyResourceTo("tests.cpp", sourceDir);

        Map<String, Object> data = new HashMap<>();
        data.put("projectName", getTestProjectName());
        data.put("extraFiles", List.of("CMakeLists.txt"));

        try (FileWriter writer = new FileWriter(new File(testProjectDir, "CMakeLists.txt"))){
            resourceHandler
                    .getTemplate("Test_CMakeLists.ftl")
                    .process(data, writer);
        } catch (IOException | TemplateException e) {
            throw new RuntimeException(e);
        }

        return this;
    }

    private void saveCMakeListsTxt() {
        Map<String, Object> data = new HashMap<>();
        data.put("projectName", projectDir.getName());
        data.put("description", description);
        data.put("sources", sources);
        data.put("vcpkgDependencies", vcpkgDependencies);
        data.put("linkLibraries", linkLibraries);
        if (testProject) {
            data.put("testProjectName", getTestProjectName());
        }
        if (!externalProjects.isEmpty()) {
            data.put("linkExternalLibraries", externalProjects);
        }
        data.put("extraFiles", extraFiles);

        try (FileWriter writer = new FileWriter(new File(projectDir, "CMakeLists.txt"))){
            resourceHandler
                    .getTemplate("CMakeLists.ftl")
                    .process(data, writer);
        } catch (IOException | TemplateException e) {
            throw new RuntimeException(e);
        }
    }

}
