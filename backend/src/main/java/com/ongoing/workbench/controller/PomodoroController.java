package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Pomodoro;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pomodoros")
public class PomodoroController extends OwnedCrudController<Pomodoro, String> {}
