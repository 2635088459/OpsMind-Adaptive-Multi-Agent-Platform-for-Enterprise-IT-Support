package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.dto.PrincipalContextView;
import com.opsmind.identity.application.query.IntrospectPrincipalContextQuery;

/** 13-package-and-class-design §Primary Input Ports; SPEC-UA-007 (Principal Claims Normalization). */
public interface IntrospectPrincipalUseCase {

    PrincipalContextView introspect(IntrospectPrincipalContextQuery query);
}
