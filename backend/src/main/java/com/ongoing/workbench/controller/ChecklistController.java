package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.ChecklistItem;
import org.springframework.web.bind.annotation.*;

/** 行动/待办的子项（清单拆分）。CRUD 全走归属基类，天然按登录用户隔离。 */
@RestController
@RequestMapping("/api/checklist")
public class ChecklistController extends OwnedCrudController<ChecklistItem, String> {}
