/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import hudson.model.CauseAction;
import hudson.model.ParametersAction;

public class PipelineToActionMapper {

    private static Map<String, ParametersAction> buildToParametersMap;
    private static Map<String, CauseAction> buildToCauseMap;

    private PipelineToActionMapper() {
    }

    static synchronized void initialize() {
        if (buildToParametersMap == null) {
            buildToParametersMap = new ConcurrentHashMap<String, ParametersAction>();
        }
        if (buildToCauseMap == null) {
            buildToCauseMap = new ConcurrentHashMap<String, CauseAction>();
        }
    }

    static synchronized void addParameterAction(String pipelineId,
            ParametersAction params) {
        buildToParametersMap.put(pipelineId, params);
    }

    static synchronized ParametersAction removeParameterAction(String pipelineId) {
        return buildToParametersMap.remove(pipelineId);
    }

    static synchronized void addCauseAction(String pipelineId, CauseAction cause) {
        buildToCauseMap.put(pipelineId, cause);
    }

    static synchronized CauseAction removeCauseAction(String pipelineId) {
        return buildToCauseMap.remove(pipelineId);
    }

}
