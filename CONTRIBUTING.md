# Contributing to ScoreSync

## Development Setup

```powershell
# Clone the repository
git clone https://github.com/yejinghua32/scoresync.git
cd scoresync

# Run in development mode
mvn spring-boot:run
```

## Running Tests

```powershell
mvn test
```

## Code Style

- Follow existing code conventions in the project
- Write JUnit tests for new functionality
- Tests should not require Spring context

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes
4. Ensure all tests pass (`mvn test`)
5. Submit a pull request

## Areas for Contribution

- Video format support (.mp4, .webm, .mov only for now)
- Scoreboard templates
- FFmpeg rendering improvements
