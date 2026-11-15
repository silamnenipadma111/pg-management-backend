package com.pgmanagement.repository;

import com.pgmanagement.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findByPgIdAndRoomNumber(Long pgId, String roomNumber);
}
