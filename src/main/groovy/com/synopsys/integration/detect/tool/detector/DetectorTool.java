/**
 * synopsys-detect
 *
 * Copyright (C) 2019 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.synopsys.integration.detect.tool.detector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.synopsys.integration.bdio.model.Forge;
import com.synopsys.integration.bdio.model.externalid.ExternalId;
import com.synopsys.integration.bdio.model.externalid.ExternalIdFactory;
import com.synopsys.integration.detect.configuration.DetectConfigurationFactory;
import com.synopsys.integration.detect.exception.DetectUserFriendlyException;
import com.synopsys.integration.detect.exitcode.ExitCodeType;
import com.synopsys.integration.detect.lifecycle.DetectContext;
import com.synopsys.integration.detect.workflow.codelocation.DetectCodeLocation;
import com.synopsys.integration.detect.workflow.codelocation.FileNameUtils;
import com.synopsys.integration.detect.workflow.event.Event;
import com.synopsys.integration.detect.workflow.event.EventSystem;
import com.synopsys.integration.detect.workflow.project.DetectorEvaluationNameVersionDecider;
import com.synopsys.integration.detect.workflow.project.DetectorNameVersionDecider;
import com.synopsys.integration.detect.workflow.report.util.DetectorEvaluationUtils;
import com.synopsys.integration.detectable.Extraction;
import com.synopsys.integration.detectable.ExtractionEnvironment;
import com.synopsys.integration.detectable.detectable.codelocation.CodeLocation;
import com.synopsys.integration.detectable.detectable.file.FileUtils;
import com.synopsys.integration.detector.base.DetectorEvaluation;
import com.synopsys.integration.detector.base.DetectorEvaluationTree;
import com.synopsys.integration.detector.base.DetectorType;
import com.synopsys.integration.detector.evaluation.DetectorEvaluator;
import com.synopsys.integration.detector.finder.DetectorFinder;
import com.synopsys.integration.detector.finder.DetectorFinderDirectoryListException;
import com.synopsys.integration.detector.finder.DetectorFinderOptions;
import com.synopsys.integration.detector.rule.DetectorRule;
import com.synopsys.integration.detector.rule.DetectorRuleSet;
import com.synopsys.integration.util.NameVersion;
import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;

public class DetectorTool {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final DetectContext detectContext;
    private final ExternalIdFactory externalIdFactory = new ExternalIdFactory();

    public DetectorTool(DetectContext detectContext) {
        this.detectContext = detectContext;
    }

    public DetectorToolResult performDetectors(File directory, DetectorFinderOptions detectorFinderOptions, String projectBomTool) throws DetectUserFriendlyException {
        logger.info("Initializing detector system.");

        EventSystem eventSystem = detectContext.getBean(EventSystem.class);
        DetectableFactory detectableFactory = detectContext.getBean(DetectableFactory.class);
        DetectorFinder detectorFinder = new DetectorFinder();
        DetectorRuleFactory detectorRuleFactory = new DetectorRuleFactory();
        DetectorRuleSet detectRuleSet = detectorRuleFactory.createRules(detectableFactory, false);//TODO add the parse flag

        Optional<DetectorEvaluationTree> possibleRootEvaluation;
        try {
            logger.info("Starting detector file system traversal.");
            possibleRootEvaluation = detectorFinder.findDetectors(directory, detectRuleSet, detectorFinderOptions);

        } catch (DetectorFinderDirectoryListException e) {
            throw new DetectUserFriendlyException("Detect was unable to list a directory while searching for detectors.", e, ExitCodeType.FAILURE_DETECTOR);
        }

        if (!possibleRootEvaluation.isPresent()){
            return new DetectorToolResult();
        }

        DetectorEvaluationTree rootEvaluation = possibleRootEvaluation.get();

        logger.info("Starting detector search.");
        DetectorEvaluator detectorEvaluator = new DetectorEvaluator();
        detectorEvaluator.searchAndApplicableEvaluation(rootEvaluation, new HashSet<>());
        eventSystem.publishEvent(Event.SearchCompleted, rootEvaluation);

        logger.info("Starting detector preparation.");
        detectorEvaluator.extractableEvaluation(rootEvaluation);
        eventSystem.publishEvent(Event.PreparationsCompleted, rootEvaluation);

        logger.info("Starting detector extraction.");
        detectorEvaluator.extractionEvaluation(rootEvaluation, detectorEvaluation -> new ExtractionEnvironment(new File("")));
        eventSystem.publishEvent(Event.ExtractionsCompleted, rootEvaluation);

        DetectorToolResult detectorToolResult = new DetectorToolResult();
        List<DetectorEvaluation> detectorEvaluations = DetectorEvaluationUtils.flatten(rootEvaluation);

        detectorToolResult.rootDetectorEvaluationTree = rootEvaluation;

        detectorToolResult.applicableDetectorTypes = detectorEvaluations.stream()
                                                         .filter(DetectorEvaluation::isApplicable)
                                                         .map(DetectorEvaluation::getDetectorRule)
                                                         .map(DetectorRule::getDetectorType)
                                                         .collect(Collectors.toSet());

        detectorToolResult.codeLocationMap = detectorEvaluations.stream()
                                                      .filter(DetectorEvaluation::wasExtractionSuccessful)
                                                      .map(it -> convert(directory, it))
                                                      .map(Map::entrySet)
                                                      .flatMap(Collection::stream)
                                                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        detectorToolResult.bomToolCodeLocations = new ArrayList<>(detectorToolResult.codeLocationMap.values());

        DetectorEvaluationNameVersionDecider detectorEvaluationNameVersionDecider = new DetectorEvaluationNameVersionDecider(new DetectorNameVersionDecider());
        Optional<NameVersion> bomToolNameVersion = detectorEvaluationNameVersionDecider.decideSuggestion(detectorEvaluations, projectBomTool);
        detectorToolResult.bomToolProjectNameVersion = bomToolNameVersion;
        logger.info("Finished evaluating detectors for project info.");

        //Completed.
        logger.info("Finished running detectors.");
        eventSystem.publishEvent(Event.DetectorsComplete, detectorToolResult);

        //detectors

        //        logger.info("Preparing to initialize detectors.");
//        DetectorFactory detectorFactory = detectContext.getBean(DetectorFactory.class);
//        EventSystem eventSystem = detectContext.getBean(EventSystem.class);
//
//        logger.info("Building detector system.");
//        DetectorSearchProvider detectorSearchProvider = new DetectorSearchProvider(detectorFactory);
//        SearchableEvaluator detectorSearchEvaluator = new SearchableEvaluator();
//
//        SearchManager searchManager = new SearchManager(searchOptions, detectorSearchProvider, detectorSearchEvaluator, eventSystem);
//        PreparationManager preparationManager = new PreparationManager(eventSystem);
//        ExtractionManager extractionManager = new ExtractionManager();
//
//        DetectorManager detectorManager = new DetectorManager(searchManager, extractionManager, preparationManager, eventSystem);
//        logger.info("Running detectors.");
//        DetectorToolResult detectorToolResult = detectorManager.runDetectors();
//        logger.info("Finished running detectors.");
//        eventSystem.publishEvent(Event.DetectorsComplete, detectorToolResult);
//
//        logger.info("Evaluating detectors for project info.");
//
//        DetectorEvaluationNameVersionDecider detectorEvaluationNameVersionDecider = new DetectorEvaluationNameVersionDecider(new DetectorNameVersionDecider());
//        Optional<NameVersion> bomToolNameVersion = detectorEvaluationNameVersionDecider.decideSuggestion(detectorToolResult.evaluatedDetectors, projectBomTool);
//        detectorToolResult.bomToolProjectNameVersion = bomToolNameVersion;
//        logger.info("Finished evaluating detectors for project info.");

        return detectorToolResult; //TODO: Fix
    }

    private Map<CodeLocation, DetectCodeLocation> convert(File detectSourcePath, DetectorEvaluation evaluation){
        Map<CodeLocation, DetectCodeLocation> detectCodeLocations = new HashMap<>();
        if (evaluation.wasExtractionSuccessful()){
            Extraction extraction = evaluation.getExtraction();
            for (CodeLocation codeLocation : extraction.getCodeLocations()){
                File sourcePath = codeLocation.getSourcePath().orElse(evaluation.getDetectableEnvironment().getDirectory());
                ExternalId externalId;
                if (!codeLocation.getExternalId().isPresent()){
                    logger.warn("The detector was unable to determine an external id for this code location, so an external id will be created using the file path.");
                    Forge detectForge = new Forge("/", "/", "Detect");
                    final String relativePath = FileNameUtils.relativize(detectSourcePath.getAbsolutePath(), sourcePath.getAbsolutePath());
                    externalId = externalIdFactory.createPathExternalId(detectForge, relativePath);
                    logger.warn("The external id that was created is: " + externalId.getExternalIdPieces().toString());
                } else {
                    externalId = codeLocation.getExternalId().get();
                }
                DetectCodeLocation detectCodeLocation = DetectCodeLocation.forDetector(codeLocation.getDependencyGraph(), sourcePath, externalId, evaluation.getDetectorRule().getDetectorType());
                detectCodeLocations.put(codeLocation, detectCodeLocation);
            }
        }
        return detectCodeLocations;
    }

    /*
    //TODO: replicate the old extraction logging...
        public ExtractionResult performExtractions(final List<DetectorEvaluation> results) {
        final List<DetectorEvaluation> extractable = results.stream().filter(result -> result.isExtractable()).collect(Collectors.toList());

        for (int i = 0; i < extractable.size(); i++) {
            final DetectorEvaluation detectorEvaluation = extractable.get(i);
            final String progress = Integer.toString((int) Math.floor((i * 100.0f) / extractable.size()));
            logger.info(String.format("Extracting %d of %d (%s%%)", i + 1, extractable.size(), progress));
            logger.info(ReportConstants.SEPERATOR);

            final ExtractionId extractionId = new ExtractionId(detectorEvaluation.getDetector().getDetectorType(), Integer.toString(i));
            detectorEvaluation.setExtractionId(extractionId);

            extract(extractable.get(i));
        }

        final Set<DetectorType> succesfulBomToolGroups = extractable.stream()
                                                             .filter(it -> it.wasExtractionSuccessful())
                                                             .map(it -> it.getDetector().getDetectorType())
                                                             .collect(Collectors.toSet());

        final Set<DetectorType> failedBomToolGroups = extractable.stream()
                                                          .filter(it -> !it.wasExtractionSuccessful())
                                                          .map(it -> it.getDetector().getDetectorType())
                                                          .collect(Collectors.toSet());

        final List<DetectCodeLocation> codeLocations = extractable.stream()
                                                           .filter(it -> it.wasExtractionSuccessful())
                                                           .flatMap(it -> it.getExtraction().codeLocations.stream())
                                                           .collect(Collectors.toList());

        return new ExtractionResult(codeLocations, succesfulBomToolGroups, failedBomToolGroups);
    }

    private void extract(final DetectorEvaluation result) { //TODO: Replace reporting.

        logger.info("Starting extraction: " + result.getDetector().getDetectorType() + " - " + result.getDetector().getName());
        logger.info("Identifier: " + result.getExtractionId().toUniqueString());
        ObjectPrinter.printObjectPrivate(new InfoLogReportWriter(), result.getDetector());
        logger.info(ReportConstants.SEPERATOR);

        try {
            result.setExtraction(result.getDetector().extract(result.getExtractionId()));
        } catch (final Exception e) {
            result.setExtraction(new Extraction.Builder().exception(e).build());
        }

        logger.info(ReportConstants.SEPERATOR);
        logger.info("Finished extraction: " + result.getExtraction().result.toString());
        logger.info("Code locations found: " + result.getExtraction().codeLocations.size());
        if (result.getExtraction().result == ExtractionResultType.EXCEPTION) {
            logger.error("Exception:", result.getExtraction().error);
        } else if (result.getExtraction().result == ExtractionResultType.FAILURE) {
            logger.info(result.getExtraction().description);
        }
        logger.info(ReportConstants.SEPERATOR);

    }

     */
}
