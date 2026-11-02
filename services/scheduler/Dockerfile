# Images for the scheduler's two deployables (ADR-0013), one target each:
#   docker build --target api       -t gridpulse-scheduler-api       services/scheduler
#   docker build --target dashboard -t gridpulse-scheduler-dashboard services/scheduler
# BuildKit builds only the stages a target needs.

# ---------------------------------------------------------------- build
FROM mcr.microsoft.com/dotnet/sdk:10.0.403 AS build
WORKDIR /src
COPY . .
# Exactly the packages in the committed lock files, as in CI.
RUN --mount=type=cache,id=nuget,target=/root/.nuget/packages,sharing=locked \
    dotnet restore GridPulse.Scheduler.slnx --locked-mode

FROM build AS publish-api
RUN --mount=type=cache,id=nuget,target=/root/.nuget/packages,sharing=locked \
    dotnet publish src/GridPulse.Scheduler.Api -c Release --no-restore -o /out

FROM build AS publish-dashboard
RUN --mount=type=cache,id=nuget,target=/root/.nuget/packages,sharing=locked \
    dotnet publish src/GridPulse.Scheduler.Dashboard -c Release --no-restore -o /out

# ---------------------------------------------------------------- runtime
# Chiseled: only the .NET runtime and what it needs. No shell, no package manager, a non-root user by default.
FROM mcr.microsoft.com/dotnet/aspnet:10.0.14-noble-chiseled AS runtime
WORKDIR /app
USER $APP_UID
EXPOSE 8080

FROM runtime AS api
COPY --from=publish-api /out .
ENTRYPOINT ["dotnet", "GridPulse.Scheduler.Api.dll"]

FROM runtime AS dashboard
COPY --from=publish-dashboard /out .
ENTRYPOINT ["dotnet", "GridPulse.Scheduler.Dashboard.dll"]
