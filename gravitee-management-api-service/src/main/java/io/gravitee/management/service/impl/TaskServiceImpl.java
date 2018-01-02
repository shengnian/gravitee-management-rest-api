/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
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
package io.gravitee.management.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.management.model.*;
import io.gravitee.management.service.*;
import io.gravitee.management.service.exceptions.TechnicalManagementException;
import io.gravitee.management.service.exceptions.UnauthorizedAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.gravitee.management.model.SubscriptionStatus.PENDING;
import static io.gravitee.management.model.permissions.RolePermission.API_SUBSCRIPTION;
import static io.gravitee.management.model.permissions.RolePermissionAction.UPDATE;

/**
 * @author Nicolas GERAUD(nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class TaskServiceImpl extends AbstractService implements TaskService {

    private final Logger LOGGER = LoggerFactory.getLogger(TaskServiceImpl.class);

    @Autowired
    ApiService apiService;

    @Autowired
    PermissionService permissionService;

    @Autowired
    SubscriptionService subscriptionService;

    @Autowired
    ApplicationService applicationService;

    @Autowired
    PlanService planService;

    @Autowired
    ObjectMapper objectMapper;

    @Override
    public TasksEntity findAll(String username) {
        if (username == null) {
            throw new UnauthorizedAccessException();
        }

        List<SubscriptionEntity> subscriptions = apiService.findByUser(username).
                stream().
                filter(api -> permissionService.hasPermission(API_SUBSCRIPTION, api.getId(), UPDATE)).
                map(api -> subscriptionService.findByApi(api.getId())).
                flatMap(Set::stream).
                filter(subscription -> PENDING.equals(subscription.getStatus())).
                collect(Collectors.toList());

        //get Properties
        Map<String, String> properties = new HashMap<>();
        subscriptions.forEach( subscription -> {
            String applicationNameKey = "APPLICATION:"+subscription.getApplication()+":name";
            if (!properties.containsKey(applicationNameKey)) {
                properties.put(applicationNameKey, applicationService.findById(subscription.getApplication()).getName());
            }

            String planKey = "PLAN:"+subscription.getPlan()+":name";
            if (!properties.containsKey(planKey)) {
                PlanEntity planEntity = planService.findById(subscription.getPlan());
                properties.put(planKey, planEntity.getName());
                String apiId = planEntity.getApis().iterator().next();
                ApiEntity api = apiService.findById(apiId);
                properties.put("PLAN:"+subscription.getPlan()+":api", apiId);
                properties.put("API:"+apiId+":name", api.getName());
            }
        });

        // convert to tasks
        List<TaskEntity> tasks = subscriptions.
                stream().
                map(this::convert).
                collect(Collectors.toList());

        TasksEntity tasksEntity = new TasksEntity();
        tasksEntity.setCount(tasks.size());
        tasksEntity.setTasks(tasks);
        tasksEntity.setProperties(properties);
        return tasksEntity;
    }

    private TaskEntity convert(SubscriptionEntity subscription) {
        TaskEntity taskEntity = new TaskEntity();
        try {
            taskEntity.setType(TaskType.SUBSCRIPTION_APPROVAL);
            taskEntity.setCreatedAt(subscription.getCreatedAt());
            taskEntity.setData(objectMapper.writeValueAsString(subscription));
        } catch (Exception e) {
            LOGGER.error("Error converting subscription {} to a Task", subscription.getId());
            throw new TechnicalManagementException("Error converting subscription " + subscription.getId() + " to a Task", e);
        }
        return taskEntity;
    }
}
