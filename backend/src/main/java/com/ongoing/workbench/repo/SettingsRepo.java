package com.ongoing.workbench.repo;

import com.ongoing.workbench.entity.Settings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepo extends JpaRepository<Settings, String> {}
