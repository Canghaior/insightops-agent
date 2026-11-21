package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.History;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskResult;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskVersion;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.EngineeringTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/{reportId}/tasks")
public class EngineeringTaskController {
    private final EngineeringTaskService tasks;
    private final ObjectMapper commands;

    public EngineeringTaskController(EngineeringTaskService tasks, ObjectMapper mapper) {
        this.tasks = tasks;
        this.commands = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping
    public ApiResponse<History> history(@PathVariable UUID reportId, HttpServletRequest r) {
        return response(r, tasks.history(CurrentAccount.actor(r), reportId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<TaskVersion> save(@PathVariable UUID reportId, @RequestBody String body, HttpServletRequest r) {
        EngineeringTaskService.TaskCommand command;
        try {
            command = commands.readValue(body, EngineeringTaskService.TaskCommand.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("任务包含无效字段或取值。");
        }
        return response(r, tasks.save(CurrentAccount.actor(r), reportId, command));
    }

    @PostMapping(path = "/versions/{taskVersionId}/results", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TaskResult> result(@PathVariable UUID reportId, @PathVariable UUID taskVersionId,
                                          @RequestParam("command") String commandJson,
                                          @RequestParam(name = "patch", required = false) MultipartFile patch,
                                          HttpServletRequest r) throws IOException {
        var actor = CurrentAccount.actor(r);
        EngineeringTaskService.ResultCommand command;
        try {
            command = commands.readValue(commandJson, EngineeringTaskService.ResultCommand.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("执行结果包含无效字段或取值。");
        }
        EngineeringTaskService.PatchFile file = null;
        if (patch != null) {
            if (patch.getSize() > EngineeringTaskService.MAX_PATCH_BYTES) {
                throw new IllegalArgumentException("补丁最多支持 1 MiB。");
            }
            try (var stream = patch.getInputStream()) {
                file = new EngineeringTaskService.PatchFile(patch.getOriginalFilename(),
                        stream.readNBytes(EngineeringTaskService.MAX_PATCH_BYTES + 1));
            }
        }
        return response(r, tasks.record(actor, reportId, taskVersionId, command, file));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static <T> ApiResponse<T> response(HttpServletRequest r, T data) {
        return new ApiResponse<>((String) r.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }
}
