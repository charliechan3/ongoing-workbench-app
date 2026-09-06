package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Media;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/media")
public class MediaController extends OwnedCrudController<Media, String> {}
