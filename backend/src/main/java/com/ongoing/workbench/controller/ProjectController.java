package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Project;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController extends OwnedCrudController<Project, String> {}
