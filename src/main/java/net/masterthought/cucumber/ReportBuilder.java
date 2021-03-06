package net.masterthought.cucumber;

import net.masterthought.cucumber.charts.FlashChartBuilder;
import net.masterthought.cucumber.charts.JsChartUtil;
import net.masterthought.cucumber.json.Feature;
import net.masterthought.cucumber.util.UnzipUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.tools.ant.DirectoryScanner;
import java.io.*;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ReportBuilder {

    ReportInformation ri;
    private File reportDirectory;
    private String buildNumber;
    private String buildProject;
    private String pluginUrlPath;
    private boolean flashCharts;
    private boolean runWithJenkins;
    private boolean artifactsEnabled;
    private boolean highCharts;
    private boolean parsingError;

    private final String VERSION = "cucumber-reporting-0.0.24 customized by Tim Bakker";

    public ReportBuilder(List<String> jsonReports, File reportDirectory, String pluginUrlPath, String buildNumber, String buildProject, boolean skippedFails, boolean undefinedFails, boolean flashCharts, boolean runWithJenkins, boolean artifactsEnabled, String artifactConfig, boolean highCharts) throws Exception {

        try {
            this.reportDirectory = reportDirectory;
            this.buildNumber = buildNumber;
            this.buildProject = buildProject;
            this.pluginUrlPath = getPluginUrlPath(pluginUrlPath);
            this.flashCharts = flashCharts;
            this.runWithJenkins = runWithJenkins;
            this.artifactsEnabled = artifactsEnabled;
            this.highCharts = highCharts;

            ConfigurationOptions.setSkippedFailsBuild(skippedFails);
            ConfigurationOptions.setUndefinedFailsBuild(undefinedFails);
            ConfigurationOptions.setArtifactsEnabled(artifactsEnabled);
            if (artifactsEnabled) {
                ArtifactProcessor artifactProcessor = new ArtifactProcessor(artifactConfig);
                ConfigurationOptions.setArtifactConfiguration(artifactProcessor.process());
            }

            ReportParser reportParser = new ReportParser(jsonReports);
            this.ri = new ReportInformation(reportParser.getFeatures());

        } catch (Exception exception) {
            parsingError = true;
            generateErrorPage(exception);
            System.out.println(exception);
        }
    }

    public boolean getBuildStatus() {
        return !(ri.getTotalNumberFailingSteps() > 0);
    }

    public void generateReports() throws Exception {
        try {
            copyResource("themes", "blue.zip");
            if (flashCharts) {
                copyResource("charts", "flash_charts.zip");
            } else {
                copyResource("charts", "js.zip");
            }
            if (artifactsEnabled) {
                copyResource("charts", "codemirror.zip");
            }
            generateFeatureOverview();
            generateFeatureReports();
            generateTagReports();
            generateTagOverview();
            generateScreenshotPage();
        } catch (Exception exception) {
            if (!parsingError) {
                generateErrorPage(exception);
                System.out.println(exception);
            }
        }
    }

    public void generateScreenshotPage() throws Exception {
        String[] pngFiles = findPNGFiles(reportDirectory);
        List<String> imagePaths = fullPathToPNGFiles(pngFiles, reportDirectory);

        //Group images
        Map<String, List>  groupedImages = groupedPNGFiles(imagePaths);

        System.out.println("[INFO BUILDER] Grouped imagepaths: " + groupedImages);

        VelocityEngine ve = new VelocityEngine();
        ve.init(getProperties());
        Template errorPage = ve.getTemplate("templates/screenshotPage.vm");
        VelocityContext context = new VelocityContext();
        context.put("version", VERSION);
        context.put("build_number", buildNumber);
        context.put("fromJenkins", runWithJenkins);
        context.put("jenkins_base", pluginUrlPath);
        context.put("build_project", buildProject);
        context.put("image_paths", imagePaths);
        context.put("grouped_images", groupedImages);

        context.put("time_stamp", new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date()));
        generateReport("screenshot-overview.html", errorPage, context);
    }


    public void generateFeatureReports() throws Exception {
        Iterator it = ri.getProjectFeatureMap().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pairs = (Map.Entry) it.next();
            List<Feature> featureList = (List<Feature>) pairs.getValue();

            for (Feature feature : featureList) {
                VelocityEngine ve = new VelocityEngine();
                ve.init(getProperties());
                Template featureResult = ve.getTemplate("templates/featureReport.vm");
                VelocityContext context = new VelocityContext();
                context.put("version", VERSION);
                context.put("feature", feature);
                context.put("report_status_colour", ri.getReportStatusColour(feature));
                context.put("build_project", buildProject);
                context.put("build_number", buildNumber);
                context.put("scenarios", feature.getElements().toList());
                context.put("time_stamp", ri.timeStamp());
                context.put("jenkins_base", pluginUrlPath);
                context.put("fromJenkins", runWithJenkins);
                context.put("artifactsEnabled", ConfigurationOptions.artifactsEnabled());
                generateReport(feature.getFileName(), featureResult, context);
            }
        }
    }

    private void generateFeatureOverview() throws Exception {
        int numberTotalPassed = ri.getTotalNumberPassingSteps();
        int numberTotalFailed = ri.getTotalNumberFailingSteps();
        int numberTotalSkipped = ri.getTotalNumberSkippedSteps();
        int numberTotalPending = ri.getTotalNumberPendingSteps();

        VelocityEngine ve = new VelocityEngine();
        ve.init(getProperties());
        Template featureOverview = ve.getTemplate("templates/featureOverview.vm");
        VelocityContext context = new VelocityContext();
        context.put("version", VERSION);
        context.put("build_project", buildProject);
        context.put("build_number", buildNumber);
        context.put("features", ri.getFeatures());
        context.put("total_features", ri.getTotalNumberOfFeatures());
        context.put("total_scenarios", ri.getTotalNumberOfScenarios());
        context.put("total_steps", ri.getTotalNumberOfSteps());
        context.put("total_passes", numberTotalPassed);
        context.put("total_fails", numberTotalFailed);
        context.put("total_skipped", numberTotalSkipped);
        context.put("total_pending", numberTotalPending);
        context.put("scenarios_passed", ri.getTotalScenariosPassed());
        context.put("scenarios_failed", ri.getTotalScenariosFailed());
        if (flashCharts) {
            context.put("step_data", FlashChartBuilder.donutChart(numberTotalPassed, numberTotalFailed, numberTotalSkipped, numberTotalPending));
            context.put("scenario_data", FlashChartBuilder.pieChart(ri.getTotalScenariosPassed(), ri.getTotalScenariosFailed()));
        } else {
            JsChartUtil pie = new JsChartUtil();
            List<String> stepColours = pie.orderStepsByValue(numberTotalPassed, numberTotalFailed, numberTotalSkipped, numberTotalPending);
            context.put("step_data", stepColours);
            List<String> scenarioColours = pie.orderScenariosByValue(ri.getTotalScenariosPassed(), ri.getTotalScenariosFailed());
            context.put("scenario_data", scenarioColours);
        }
        context.put("time_stamp", ri.timeStamp());
        context.put("total_duration", ri.getTotalDurationAsString());
        context.put("jenkins_base", pluginUrlPath);
        context.put("fromJenkins", runWithJenkins);
        context.put("flashCharts", flashCharts);
        context.put("highCharts", highCharts);
        generateReport("feature-overview.html", featureOverview, context);
    }


    public void generateTagReports() throws Exception {
        for (TagObject tagObject : ri.getTags()) {
            VelocityEngine ve = new VelocityEngine();
            ve.init(getProperties());
            Template featureResult = ve.getTemplate("templates/tagReport.vm");
            VelocityContext context = new VelocityContext();
            context.put("version", VERSION);
            context.put("tag", tagObject);
            context.put("time_stamp", ri.timeStamp());
            context.put("jenkins_base", pluginUrlPath);
            context.put("build_project", buildProject);
            context.put("build_number", buildNumber);
            context.put("fromJenkins", runWithJenkins);
            context.put("report_status_colour", ri.getTagReportStatusColour(tagObject));
            generateReport(tagObject.getTagName().replace("@", "").trim() + ".html", featureResult, context);
        }
    }

    public void generateTagOverview() throws Exception {
        VelocityEngine ve = new VelocityEngine();
        ve.init(getProperties());
        Template featureOverview = ve.getTemplate("templates/tagOverview.vm");
        VelocityContext context = new VelocityContext();
        context.put("version", VERSION);
        context.put("build_project", buildProject);
        context.put("build_number", buildNumber);
        context.put("tags", ri.getTags());
        context.put("total_tags", ri.getTotalTags());
        context.put("total_scenarios", ri.getTotalTagScenarios());
        context.put("total_passed_scenarios", ri.getTotalPassingTagScenarios());
        context.put("total_failed_scenarios", ri.getTotalFailingTagScenarios());
        context.put("total_steps", ri.getTotalTagSteps());
        context.put("total_passes", ri.getTotalTagPasses());
        context.put("total_fails", ri.getTotalTagFails());
        context.put("total_skipped", ri.getTotalTagSkipped());
        context.put("total_pending", ri.getTotalTagPending());
        if (flashCharts) {
            context.put("chart_data", FlashChartBuilder.StackedColumnChart(ri.tagMap));
        } else {
            if (highCharts) {
                context.put("chart_categories", JsChartUtil.getTags(ri.tagMap));
                context.put("chart_data", JsChartUtil.generateTagChartDataForHighCharts(ri.tagMap));
            } else {
                context.put("chart_rows", JsChartUtil.generateTagChartData(ri.tagMap));
            }
        }
        context.put("total_duration", ri.getTotalTagDuration());
        context.put("time_stamp", ri.timeStamp());
        context.put("jenkins_base", pluginUrlPath);
        context.put("fromJenkins", runWithJenkins);
        context.put("flashCharts", flashCharts);
        context.put("highCharts", highCharts);
        generateReport("tag-overview.html", featureOverview, context);
    }

    public void generateErrorPage(Exception exception) throws Exception {
        VelocityEngine ve = new VelocityEngine();
        ve.init(getProperties());
        Template errorPage = ve.getTemplate("templates/errorPage.vm");
        VelocityContext context = new VelocityContext();
        context.put("version", VERSION);
        context.put("build_number", buildNumber);
        context.put("fromJenkins", runWithJenkins);
        context.put("jenkins_base", pluginUrlPath);
        context.put("build_project", buildProject);
        context.put("error_message", exception);
        context.put("time_stamp", new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date()));
        generateReport("feature-overview.html", errorPage, context);
    }

    private void copyResource(String resourceLocation, String resourceName) throws IOException, URISyntaxException {
        final File tmpResourcesArchive = File.createTempFile("temp", resourceName + ".zip");

        InputStream resourceArchiveInputStream = ReportBuilder.class.getResourceAsStream(resourceLocation + "/" + resourceName);
        if (resourceArchiveInputStream == null) {
            resourceArchiveInputStream = ReportBuilder.class.getResourceAsStream("/" + resourceLocation + "/" + resourceName);
        }
        OutputStream resourceArchiveOutputStream = new FileOutputStream(tmpResourcesArchive);
        try {
            IOUtils.copy(resourceArchiveInputStream, resourceArchiveOutputStream);
        } finally {
            IOUtils.closeQuietly(resourceArchiveInputStream);
            IOUtils.closeQuietly(resourceArchiveOutputStream);
        }
        UnzipUtils.unzipToFile(tmpResourcesArchive, reportDirectory);
        FileUtils.deleteQuietly(tmpResourcesArchive);
    }

    private String getPluginUrlPath(String path) {
        return path.isEmpty() ? "/" : path;
    }

    private void generateReport(String fileName, Template featureResult, VelocityContext context) throws Exception {
        FileOutputStream fileStream = new FileOutputStream(new File(reportDirectory, fileName));
        OutputStreamWriter writer = new OutputStreamWriter(fileStream, "UTF-8");
        featureResult.merge(context, writer);
        writer.flush();
        writer.close();
    }

    private Properties getProperties() {
        Properties props = new Properties();
        props.setProperty("resource.loader", "class");
        props.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        props.setProperty("runtime.log", new File(reportDirectory, "velocity.log").getPath());
        return props;
    }



    private String[] findPNGFiles(File targetDirectory) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setIncludes(new String[]{"**/*.png"});
        scanner.setBasedir(targetDirectory);
        scanner.scan();
        return scanner.getIncludedFiles();
    }

    private List<String> fullPathToPNGFiles(String[] imageFiles, File targetBuildDirectory) {
        List<String> fullPathList = new ArrayList<String>();
        for (String file : imageFiles) {

            if (file.contains("screen")) {
                String absolutePath = new File(targetBuildDirectory, file).getAbsolutePath();
                 //Fix Jobs path
                absolutePath = absolutePath.replace("var/lib/jenkins/jobs", "jenkins/job");
                //Replace date with build number
                absolutePath = absolutePath.replaceFirst("builds/[\\d\\-_]+/", this.buildNumber);

                absolutePath = absolutePath.replace("cucumber-html-reports", "/cucumber-html-reports");

                fullPathList.add(absolutePath);
            }

        }
        return fullPathList;
    }

    private Map<String, List>  groupedPNGFiles(List<String> pngFiles) {

        Map<String, List> dictionary = new HashMap<String, List>();

        for (String file : pngFiles) {
            String scenarioName = scenarioNameForImagePath(file);

            List<String> scenarioList = (List) dictionary.get(scenarioName);
            if (scenarioList == null) {
                scenarioList = new ArrayList<String>();
            }
            scenarioList.add(file);

            dictionary.put(scenarioName, scenarioList);
        }

        return dictionary;
    }

    private String scenarioNameForImagePath(String completePath) {
        String scenarioName = "";
        String[] completeParts = completePath.split("screen");

        String[] parts = completeParts[1].split("__");
        scenarioName = parts[0];
        return scenarioName;
    }
}