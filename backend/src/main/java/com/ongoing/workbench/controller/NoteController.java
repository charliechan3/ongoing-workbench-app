package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Note;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notes")
public class NoteController extends OwnedCrudController<Note, String> {}
