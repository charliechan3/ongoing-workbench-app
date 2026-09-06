package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.WorkLog;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/worklogs")
public class WorkLogController extends OwnedCrudController<WorkLog, String> {}
