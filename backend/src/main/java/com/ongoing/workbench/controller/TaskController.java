package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Task;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class TaskController extends OwnedCrudController<Task, String> {}
