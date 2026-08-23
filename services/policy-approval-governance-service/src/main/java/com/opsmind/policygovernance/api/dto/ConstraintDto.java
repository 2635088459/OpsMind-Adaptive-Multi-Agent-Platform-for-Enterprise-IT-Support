package com.opsmind.policygovernance.api.dto;

import com.opsmind.policygovernance.domain.decision.Constraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConstraintDto(
    @NotNull Constraint.Type type,
    @NotBlank String detail
) {

    public static ConstraintDto from(Constraint constraint) {
        return new ConstraintDto(constraint.type(), constraint.detail());
    }

    public Constraint toDomain() {
        return new Constraint(type, detail);
    }
}
