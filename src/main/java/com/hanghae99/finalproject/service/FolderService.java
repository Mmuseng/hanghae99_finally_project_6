package com.hanghae99.finalproject.service;

import com.hanghae99.finalproject.exceptionHandler.CustumException.CustomException;
import com.hanghae99.finalproject.model.dto.requestDto.*;
import com.hanghae99.finalproject.model.dto.responseDto.*;
import com.hanghae99.finalproject.model.entity.*;
import com.hanghae99.finalproject.model.repository.*;
import com.hanghae99.finalproject.model.resultType.*;
import com.hanghae99.finalproject.util.UserinfoHttpRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.hanghae99.finalproject.exceptionHandler.CustumException.ErrorCode.*;
import static com.hanghae99.finalproject.model.resultType.CategoryType.ALL;
import static com.hanghae99.finalproject.model.resultType.FileUploadType.BOARD;

@Service
@RequiredArgsConstructor
public class FolderService {

    private final FolderRepository folderRepository;
    private final UserinfoHttpRequest userinfoHttpRequest;
    private final BoardService boardService;
    private final ShareRepository shareRepository;
    private final UserRepository userRepository;
    private final S3Uploader s3Uploader;
    private final ReportRepository reportRepository;
    private final ImageRepository imageRepository;
    private final BoardRepository boardRepository;

    @Transactional
    public Folder folderSave(FolderRequestDto folderRequestDto, HttpServletRequest request) {
        if (folderRequestDto.getName().trim().equals("무제")) {
            throw new RuntimeException("무제라는 이름은 추가할 수 없습니다.");
        }

        Users user = userinfoHttpRequest.userFindByToken(request);
        user.setFolderCnt(user.getFolderCnt() + 1);
        return folderRepository.save(
                new Folder(
                        folderRequestDto,
                        user,
                        folderRepository.findFolderCount(user.getId())
                )
        );
    }

    @Transactional(readOnly = true)
    public Folder findFolder(Long folderId, HttpServletRequest request) {
        return folderRepository.findByIdAndUsersId(
                        folderId,
                        userinfoHttpRequest.userFindByToken(request).getId()
                )
                .orElseThrow(() -> new RuntimeException("에러, 찾는 폴더가 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<Folder> findAllFolder(List<Long> folderRequestDto, HttpServletRequest request) {
        return folderRepository.findAllByIdInAndUsersId(
                        folderRequestDto,
                        userinfoHttpRequest.userFindByToken(request).getId()
                )
                .orElseThrow(() -> new RuntimeException("에러, 찾는 폴더가 없습니다."));
    }

    @Transactional
    public void folderDelete(List<FolderRequestDto> folderRequestDto, HttpServletRequest request) {
        Users users = userinfoHttpRequest.userFindByToken(request);
        List<Long> longs = folderRequestDto.stream()
                .map(FolderRequestDto::getId)
                .collect(Collectors.toList());

        List<Folder> folders = findAllFolder(longs, request);

        List<Long> DbLongList = folders.stream()
                .map(Folder::getId)
                .collect(Collectors.toList());

        for (Folder folder : folders) {
            if (folder.getName().equals("무제")) {
                throw new RuntimeException("무제폴더는 삭제할 수 없습니다.");
            }
            userinfoHttpRequest.userAndWriterMatches(
                    folder.getUsers().getId(),
                    users.getId()
            );
        }
        reportRepository.deleteAllByBadfolderIdIn(DbLongList);
        shareRepository.deleteAllByFolderIdIn(DbLongList);
        List<Board> removeBoardList = boardService.boardDeleteByFolderId(DbLongList);
        users.setBoardCnt(users.getBoardCnt() - removeBoardList.size());
        users.setFolderCnt(users.getFolderCnt() - DbLongList.size());
    }

    @Transactional
    public void folderUpdate(Long folderId, HttpServletRequest request, FolderRequestDto folderRequestDto) {
        Folder folder = findFolder(
                folderId,
                request
        );

        if (folder.getName().equals("무제")) {
            throw new RuntimeException("무제 폴더는 이름을 수정할 수 없습니다.");
        }

        userinfoHttpRequest.userAndWriterMatches(
                folder.getUsers().getId(),
                userinfoHttpRequest.userFindByToken(request).getId()
        );

        boardService.statusUpdateByFolderId(folderId, folderRequestDto);
        folder.update(folderRequestDto);
    }

    @Transactional
    public MessageResponseDto crateBoardInFolder(BoardRequestDto boardRequestDto, HttpServletRequest request) {
        Image saveImage = new Image();

        Folder folder = findFolder(
                boardRequestDto.getFolderId(),
                request
        );

        if (boardRequestDto.getBoardType() == BoardType.LINK) {
            boardRequestDto.ogTagToBoardRequestDto(
                    boardService.thumbnailLoad(boardRequestDto.getLink()),
                    boardRequestDto.getLink()
            );

            if (!boardRequestDto.getImgPath().equals("") && boardRequestDto.getImgPath() != null) {
                boardRequestDto.updateImagePath(s3Uploader.upload(BOARD.getPath(), boardRequestDto.getImgPath()).getUrl());
            }

        } else if (boardRequestDto.getBoardType() == BoardType.MEMO) {
            boardRequestDto.updateTitle(new SimpleDateFormat(DateType.YEAR_MONTH_DAY.getPattern()).format(new Date()));
        }

        Users user = userinfoHttpRequest.userFindByToken(request);
        Board board = boardRepository.save(new Board(boardRequestDto, folder.getBoardCnt() + 1, user, folder));

        if (boardRequestDto.getBoardType() == BoardType.LINK) {
            saveImage = imageRepository.save(
                    new Image(
                            board,
                            ImageType.OG
                    )
            );
        }

        folder.setBoardCnt(folder.getBoardCnt() + 1);
        user.setBoardCnt(user.getBoardCnt() + 1);
        boardRequestDto.updateFolderName(folder.getName());
        return new MessageResponseDto(
                200,
                "저장이 완료 되었습니다.",
                new BoardResponseDto(
                        board,
                        boardRequestDto,
                        new ImageRequestDto(saveImage)
                )
        );
    }

    @Transactional
    public MessageResponseDto shareFolder(Long folderId, HttpServletRequest request) {
        Users users = userinfoHttpRequest.userFindByToken(request);
        Folder folder = findShareFolder(folderId, request);
        Optional<Share> findShare = shareRepository.findByFolderIdAndUsersId(folderId, users.getId());
        if (!findShare.isPresent()) {
            Share share = new Share(folder, users);
            shareRepository.save(share);
            return new MessageResponseDto<>(200, "공유되었습니다.");
        }
        return new MessageResponseDto<>(501, "이미 공유된 모음입니다.");
    }

    private Folder findShareFolder(Long folderId, HttpServletRequest request) {
        return folderRepository.findByIdAndUsersIdNot(folderId, userinfoHttpRequest.userFindByToken(request).getId()).orElseThrow(()
                -> new RuntimeException("원하는 폴더를 찾지 못했습니다."));
    }

    //    @Transactional
    //    public void cloneFolder(Long folderId, HttpServletRequest request) {
    //        Users users = userinfoHttpRequest.userFindByToken(request);
    //        Folder folder = findShareFolder(folderId, request);
    //        List<Board> boards = boardService.findAllById(folder);
    //        FolderRequestDto folderRequestDto = new FolderRequestDto(folder);
    //
    //        Folder savefolder = folderRepository.save(new Folder(folderRequestDto, users));
    //        List<Board> boards1 = new ArrayList<>();
    //        for (Board board : boards) {
    //            boards1.add(new Board(board, users, savefolder));
    //        }
    //        boardRepository.saveAll(boards1);
    //        users.setFolderCnt(users.getFolderCnt() + 1);
    //        users.setBoardCnt(users.getBoardCnt() + savefolder.getBoardCnt());
    //    }

    @Transactional
    public void folderOrderChange(OrderRequestDto orderRequestDto, HttpServletRequest request) {
        Folder folder = folderRepository.findById(orderRequestDto.getFolderId())
                .orElseThrow(() -> new RuntimeException("없는 게시물입니다."));
        Users users = userinfoHttpRequest.userFindByToken(request);
        if (folder.getUsers().getId() != users.getId()) {
            throw new RuntimeException("폴더 생성자가 아닙니다.");
        }

        if (folder.getFolderOrder() == orderRequestDto.getAfterOrder() || users.getFolderCnt() + 1 < orderRequestDto.getAfterOrder()) {
            throw new RuntimeException("잘못된 정보입니다. 기존 order : " + folder.getFolderOrder() + " , 바꾸는 order : " + orderRequestDto.getAfterOrder() + " , forder 최종 order : " + users.getFolderCnt());
        } else if (folder.getFolderOrder() - orderRequestDto.getAfterOrder() > 0) {
            folderRepository.updateOrderSum(folder.getFolderOrder(), orderRequestDto.getAfterOrder());
        } else {
            folderRepository.updateOrderMinus(folder.getFolderOrder(), orderRequestDto.getAfterOrder());
        }

        folder.updateOrder(orderRequestDto.getAfterOrder());

    }

    @Transactional(readOnly = true)
    public List<Folder> folders() {
        return folderRepository.findAll();
    }

    @Transactional
    public List<FolderResponseDto> findBestFolder(int page, int size, HttpServletRequest request) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("sharedCount").descending());
        Users users = userinfoHttpRequest.userFindByToken(request);
        List<Folder> folders = folderRepository.findAllBystatus(users.getId(), DisclosureStatusType.PUBLIC, pageRequest).getContent();
        List<FolderResponseDto> folderResponseDtos = new ArrayList<>();
        for (Folder folder : folders) {
            folderResponseDtos.add(new FolderResponseDto(folder));
        }
        return folderResponseDtos;
    }

    public Folder findByBasicFolder(Users users) {
        return folderRepository.findByUsersAndName(users, "무제");
    }

    @Transactional(readOnly = true)
    public MessageResponseDto moum(String keyword,
                                   HttpServletRequest request,
                                   Pageable pageable,
                                   Long userId,
                                   List<FolderRequestDto> folderRequestDtos) {
        List<DisclosureStatusType> disclosureStatusTypes = new ArrayList<>();
        disclosureStatusTypes.add(DisclosureStatusType.PUBLIC);

        Users users = userRepository.findById(userId)
                .orElseGet(() -> {
                    if (userId == 0L) {
                        disclosureStatusTypes.add(DisclosureStatusType.PRIVATE);
                        return userinfoHttpRequest.userFindByToken(request);
                    }
                    throw new CustomException(NOT_FIND_USER);
                });

        Optional<FolderRequestDto> findAllCategory = folderRequestDtos.stream()
                .filter(categoryType -> categoryType.getCategory() == ALL)
                .findFirst();

        if (findAllCategory.isPresent()) {
            return pageEntityListToDtoListForFolder(
                    folderRepository.findByNameContaining(
                            "%" + keyword + "%",
                            users,
                            boardService.findByFolder(findByBasicFolder(users)).size() > 0,
                            disclosureStatusTypes,
                            pageable
                    )
            );
        }
        return pageEntityListToDtoListForFolder(
                folderRepository.findByNameContaining(
                        "%" + keyword + "%",
                        users,
                        boardService.findByFolder(findByBasicFolder(users)).size() > 0,
                        disclosureStatusTypes,
                        boardService.findSelectCategory(folderRequestDtos),
                        pageable
                )
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, CategoryType>> findCategoryList(Long userId, HttpServletRequest request) {
        Users users = userRepository.findById(userId)
                .orElseGet(() -> {
                    if (userId == 0L) {
                        return userinfoHttpRequest.userFindByToken(request);
                    }
                    throw new RuntimeException("회원을 찾을 수 없습니다.");
                });
        return boardService.findCategoryByUsersId(users.getId());
    }

    @Transactional(readOnly = true)
    public MessageResponseDto shareList(String keyword, HttpServletRequest request, Pageable pageable, Long userId) {
        List<DisclosureStatusType> disclosureStatusTypes = new ArrayList<>();
        disclosureStatusTypes.add(DisclosureStatusType.PUBLIC);

        Users users = userRepository.findById(userId)
                .orElseGet(() -> {
                    if (userId == 0L) {
                        disclosureStatusTypes.add(DisclosureStatusType.PRIVATE);
                        return userinfoHttpRequest.userFindByToken(request);
                    }
                    throw new CustomException(NOT_FIND_USER);
                });

        return pageEntityListToDtoListForFolder(
                folderRepository.findAllByIdAndNameLike(
                        listToId(shareRepository.findAllByUsersId(users.getId())),
                        "%" + keyword + "%",
                        pageable
                )
        );

    }

    private List<FolderResponseDto> entityListToDtoListForFolder(List<Folder> content) {
        List<FolderResponseDto> folderResponseDtos = new ArrayList<>();

        for (Folder folder : content) {
            folderResponseDtos.add(new FolderResponseDto(folder));
        }
        return folderResponseDtos;
    }

    private MessageResponseDto pageEntityListToDtoListForFolder(Page<Folder> content) {
        List<FolderResponseDto> folderResponseDtos = new ArrayList<>();
        List<Folder> contentList = content.getContent();
        for (Folder folder : contentList) {
            folderResponseDtos.add(new FolderResponseDto(folder));
        }
        return new MessageResponseDto(200, "조회 완료", folderResponseDtos, content.getTotalPages());
    }

    public FolderListResponseDto allFolders(String keyword, HttpServletRequest request, Pageable pageable) {
        Users users = userinfoHttpRequest.userFindByToken(request);

        Page<Folder> folders = folderRepository.findAllByNameContaining1(
                users.getId(),
                DisclosureStatusType.PUBLIC,
                "%" + keyword + "%",
                pageable
        );

        return new FolderListResponseDto(getFolder(folders.getContent()), folders.getTotalElements());
    }

    public List<FolderResponseDto> getFolder(List<Folder> folders) {
        List<FolderResponseDto> folderResponseDtos = new ArrayList<>();

        for (Folder folder : folders) {
            FolderResponseDto folderResponseDto = new FolderResponseDto(folder);
            folderResponseDtos.add(folderResponseDto);
        }

        return folderResponseDtos;
    }

    private List<Long> listToId(List<Share> List) {
        return List.stream()
                .map(Share::getFolder)
                .map(Folder::getId)
                .collect(Collectors.toList());
    }

    @Transactional
    public void reportFolder(Long folderId, HttpServletRequest request) {
        Users users = userinfoHttpRequest.userFindByToken(request);

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new CustomException(NOT_FIND_FOLDER));

        Users baduser = folder.getUsers();
        reportRepository.save(new Report(users, folder));
        folder.setReportCnt(folder.getReportCnt() + 1);
        baduser.setReportCnt(baduser.getReportCnt() + 1);
    }

    @Transactional(readOnly = true)
    public List<FolderResponseDto> findAllFolderList(String status, Long userId, HttpServletRequest request) {
        List<DisclosureStatusType> disclosureStatusTypes = new ArrayList<>();
        disclosureStatusTypes.add(DisclosureStatusType.PUBLIC);

        Users users = userRepository.findById(userId)
                .orElseGet(() -> {
                    if (userId == 0L) {
                        disclosureStatusTypes.add(DisclosureStatusType.PRIVATE);
                        return userinfoHttpRequest.userFindByToken(request);
                    }
                    throw new CustomException(NOT_FIND_USER);
                });

        return entityListToDtoListForFolder(
                folderRepository.findFolderList(
                        users.getId(),
                        status,
                        disclosureStatusTypes
                )
        );
    }

    @Transactional
    public Folder updateStatus(FolderRequestDto folderRequestDto, HttpServletRequest request) {
        Users users = userinfoHttpRequest.userFindByToken(request);

        Folder folder = folderRepository.findFolderByIdAndUsersId(
                folderRequestDto.getId(),
                users.getId()
        ).orElseThrow(() -> new CustomException(NOT_FIND_FOLDER));

        folder.updateStatus(folderRequestDto.getStatus());

        return folder;
    }

    @Transactional
    public void deleteShare(Long folderId, HttpServletRequest request) {
        Users user = userinfoHttpRequest.userFindByToken(request);
        Share share = shareRepository.findByFolderIdAndUsersId(folderId, user.getId())
                .orElseThrow(() -> new CustomException(NOT_FIND_SHARE));
        shareRepository.delete(share);
    }
}
