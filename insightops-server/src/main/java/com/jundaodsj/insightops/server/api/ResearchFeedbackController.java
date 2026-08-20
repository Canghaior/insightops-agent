package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.conversation.application.ResearchFeedbackStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/research-feedback")
public class ResearchFeedbackController {
    private final ResearchFeedbackStore store;
    public ResearchFeedbackController(ResearchFeedbackStore store) { this.store=store; }

    @PutMapping("/runs/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answer(@PathVariable UUID runId,@Valid @RequestBody AnswerRequest body,HttpServletRequest request){
        if(!store.saveAnswerFeedback(CurrentAccount.actor(request),runId,body.helpful(),body.reason(),body.comment(),Instant.now()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent run not found");
    }

    @PutMapping("/runs/{runId}/citations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void citation(@PathVariable UUID runId,@Valid @RequestBody CitationRequest body,HttpServletRequest request){
        try {
            if(!store.saveCitationFeedback(CurrentAccount.actor(request),runId,body.citationUrl(),body.correct(),body.comment(),Instant.now()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent run not found");
        } catch(IllegalArgumentException exception){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,exception.getMessage());
        }
    }

    public record AnswerRequest(Boolean helpful,@Size(max=48)String reason,@Size(max=1000)String comment){}
    public record CitationRequest(@Size(min=8,max=1024)String citationUrl,boolean correct,@Size(max=1000)String comment){}
}
