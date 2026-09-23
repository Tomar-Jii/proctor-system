const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.use(express.static(path.join(__dirname, 'public')));

const students = new Map();
const admins = new Set();
let currentViewedStudent = null;

io.on('connection', (socket) => {
  socket.on('register', ({ role, id }) => {
    socket.role = role;
    socket.customId = id;

    if (role === 'student') {
      students.set(id, socket.id);
      io.to(Array.from(admins)).emit('student-list', Array.from(students.keys()));
    } else if (role === 'admin') {
      admins.add(socket.id);
      socket.emit('student-list', Array.from(students.keys()));
    }
  });

  socket.on('request-stream', ({ studentId }) => {
    if (currentViewedStudent && currentViewedStudent !== studentId) {
      const prev = students.get(currentViewedStudent);
      if (prev) io.to(prev).emit('stop-stream');
    }
    currentViewedStudent = studentId;
    const targetSocket = students.get(studentId);
    if (targetSocket) {
      io.to(targetSocket).emit('start-stream');
    }
  });

  socket.on('stream-frame', (base64) => {
    io.to(Array.from(admins)).emit('frame', {
      studentId: socket.customId,
      image: base64
    });
  });

  socket.on('stop-stream', ({ studentId }) => {
    const targetSocket = students.get(studentId);
    if (targetSocket) {
      io.to(targetSocket).emit('stop-stream');
    }
    if (currentViewedStudent === studentId) currentViewedStudent = null;
  });

  socket.on('disconnect', () => {
    if (socket.role === 'student') {
      students.delete(socket.customId);
      io.to(Array.from(admins)).emit('student-list', Array.from(students.keys()));
      if (currentViewedStudent === socket.customId) {
        currentViewedStudent = null;
        io.to(Array.from(admins)).emit('student-offline', socket.customId);
      }
    } else if (socket.role === 'admin') {
      admins.delete(socket.id);
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`Server live on port ${PORT}`));
