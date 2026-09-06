package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Action;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/actions")
public class ActionController extends OwnedCrudController<Action, String> {}
