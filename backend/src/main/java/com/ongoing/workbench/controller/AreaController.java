package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Area;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/areas")
public class AreaController extends OwnedCrudController<Area, String> {}
