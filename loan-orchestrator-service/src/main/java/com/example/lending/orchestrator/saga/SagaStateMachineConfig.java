package com.example.lending.orchestrator.saga;

import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
public class SagaStateMachineConfig extends EnumStateMachineConfigurerAdapter<SagaState, SagaEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<SagaState, SagaEvent> states) throws Exception {
        states.withStates()
                .initial(SagaState.INIT)
                .end(SagaState.COMPLETED)
                .end(SagaState.FAILED)
                .states(EnumSet.allOf(SagaState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<SagaState, SagaEvent> transitions) throws Exception {
        transitions
            .withExternal()
                .source(SagaState.INIT).target(SagaState.KYC_REQUESTED)
                .event(SagaEvent.START_SAGA)
            .and().withExternal()
                .source(SagaState.KYC_REQUESTED).target(SagaState.CREDIT_REQUESTED)
                .event(SagaEvent.KYC_PASSED)
            .and().withExternal()
                .source(SagaState.KYC_REQUESTED).target(SagaState.FAILED)
                .event(SagaEvent.KYC_FAILED)
            .and().withExternal()
                .source(SagaState.CREDIT_REQUESTED).target(SagaState.DISBURSEMENT_REQUESTED)
                .event(SagaEvent.CREDIT_APPROVED)
            .and().withExternal()
                .source(SagaState.CREDIT_REQUESTED).target(SagaState.FAILED)
                .event(SagaEvent.CREDIT_REJECTED)
            .and().withExternal()
                .source(SagaState.DISBURSEMENT_REQUESTED).target(SagaState.COMPLETED)
                .event(SagaEvent.DISBURSEMENT_SUCCEEDED)
            .and().withExternal()
                .source(SagaState.DISBURSEMENT_REQUESTED).target(SagaState.COMPENSATING_CREDIT)
                .event(SagaEvent.DISBURSEMENT_FAILED)
            .and().withExternal()
                .source(SagaState.COMPENSATING_CREDIT).target(SagaState.FAILED)
                .event(SagaEvent.CREDIT_CANCELLED)
            .and().withExternal()
                .source(SagaState.KYC_REQUESTED).target(SagaState.FAILED)
                .event(SagaEvent.TIMEOUT)
            .and().withExternal()
                .source(SagaState.CREDIT_REQUESTED).target(SagaState.FAILED)
                .event(SagaEvent.TIMEOUT)
            .and().withExternal()
                .source(SagaState.DISBURSEMENT_REQUESTED).target(SagaState.FAILED)
                .event(SagaEvent.TIMEOUT);
    }
}
