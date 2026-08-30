package com.xscsiem.hsiem_platform.soar.playbook.validation;

import com.xscsiem.hsiem_platform.soar.PlaybookGraph;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandlerRegistry;

import java.util.List;

public record SoarValidationContext(String entryType, List<String> eventTypes,
                                    PlaybookGraph graph, SoarNodeHandlerRegistry handlers) { }
