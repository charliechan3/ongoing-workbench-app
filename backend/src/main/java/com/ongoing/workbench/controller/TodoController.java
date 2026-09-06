package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Todo;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/todos")
public class TodoController extends OwnedCrudController<Todo, String> {}
